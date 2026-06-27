/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ai.llm;

import com.google.gson.*;
import org.jackhuang.hmcl.ai.LlmConfig;
import org.jackhuang.hmcl.ai.langchain4j.AiChatClient;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/// OpenAI-compatible chat completion HTTP client with both streaming and non-streaming support.
///
/// All HTTP calls run on a background executor so the calling thread is never blocked.
/// The client is constructed with an [`LlmConfig`] that provides the endpoint, API key,
/// model, generation parameters, and timeout. Instances are thread-safe and should be
/// reused for multiple requests.
///
/// ## SSE streaming
///
/// Streaming uses Server-Sent Events: lines prefixed with `data: ` carry JSON chunks
/// whose `choices[0].delta.content` field contains the token text. The stream ends when
/// `data: [DONE]` is received.
///
/// ## Error mapping
///
/// | Status   | Condition                 | Message              |
/// |----------|---------------------------|----------------------|
/// | 401      | Unauthorized              | Authentication error |
/// | 429      | Too Many Requests         | Rate limit exceeded  |
/// | 5xx      | Server Error              | Server error         |
/// | 0        | IOException / interrupted | Network error        |
/// | other    | Any other HTTP status     | HTTP error + code    |
///
/// @see LlmConfig
/// @see LlmMessage
/// @see LlmStreamCallback
@NotNullByDefault
public final class LlmClient implements AiChatClient {

    private static final Gson GSON = new Gson();

    private final LlmConfig config;
    private final HttpClient httpClient;
    private final ExecutorService executor;

    /// Creates a client bound to the given configuration.
    ///
    /// The underlying `HttpClient` is configured with the timeout from
    /// [`config.getTimeout()`][LlmConfig#getTimeout].
    ///
    /// @param config the configuration providing endpoint, API key, model, and timeout
    public LlmClient(LlmConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.getTimeout())
                .build();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "llm-client");
            t.setDaemon(true);
            return t;
        });
    }

    /// Sends a non-streaming chat completion request and returns the full response text.
    ///
    /// The returned `CompletableFuture` completes on a background thread; the calling
    /// thread is never blocked.
    ///
    /// @param messages the conversation history to send to the model
    /// @return a future that yields the assistant's response text
    /// @throws LlmException if the request fails (wrapped in the future)
    @Override
    public CompletableFuture<@Nullable String> sendMessage(List<LlmMessage> messages) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String body = buildRequestBody(messages, false);
                HttpRequest request = buildRequest(body);
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return parseNonStreamingResponse(response.body());
                } else {
                    throw mapHttpError(status, response.body());
                }
            } catch (IOException e) {
                throw new RuntimeException(new LlmException("Network error", 0, e));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(new LlmException("Request interrupted", 0, e));
            } catch (LlmException e) {
                throw new RuntimeException(e);
            }
        }, executor).thenApply(result -> result).exceptionally(ex -> {
            Throwable cause = ex.getCause();
            if (cause instanceof LlmException) {
                throw new RuntimeException(cause);
            }
            throw new RuntimeException(ex);
        });
    }

    /// Sends a streaming chat completion request. Tokens are delivered to [callback]
    /// as they arrive via Server-Sent Events.
    ///
    /// The underlying HTTP request and SSE parsing run on a background thread;
    /// the calling thread returns immediately.
    ///
    /// @param messages the conversation history to send to the model
    /// @param callback the callback to receive tokens, completion, and errors
    @Override
    public void sendMessageStreaming(List<LlmMessage> messages, LlmStreamCallback callback) {
        executor.execute(() -> {
            try {
                String body = buildRequestBody(messages, true);
                HttpRequest request = buildRequest(body);

                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();

                if (status < 200 || status >= 300) {
                    String errorBody = readAll(response.body());
                    callback.onError(mapHttpError(status, errorBody));
                    return;
                }

                parseSseStream(response.body(), callback);
            } catch (IOException e) {
                callback.onError(new LlmException("Network error", 0, e));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                callback.onError(new LlmException("Request interrupted", 0, e));
            }
        });
    }

    // ---- Request construction ---------------------------------------------------

    /// Builds the JSON request body including all advanced generation parameters
    /// from the current configuration, conditionally omitting fields the provider
    /// may not support.
    ///
    /// @param messages the conversation history
    /// @param stream   whether this is a streaming request
    /// @return the JSON request body as a string
    private String buildRequestBody(List<LlmMessage> messages, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.getModel());
        body.add("messages", GSON.toJsonTree(messages));
        body.addProperty("max_tokens", config.getMaxTokens());
        body.addProperty("temperature", config.getTemperature());
        body.addProperty("top_p", config.getTopP());
        body.addProperty("stream", stream);

        // Optionally include advanced parameters when non-default.
        if (config.getPresencePenalty() != LlmConfig.DEFAULT_PRESENCE_PENALTY) {
            body.addProperty("presence_penalty", config.getPresencePenalty());
        }
        if (config.getFrequencyPenalty() != LlmConfig.DEFAULT_FREQUENCY_PENALTY) {
            body.addProperty("frequency_penalty", config.getFrequencyPenalty());
        }

        Long seed = config.getSeed();
        if (seed != null) {
            body.addProperty("seed", seed);
        }

        String reasoningEffort = config.getReasoningEffort();
        if (reasoningEffort != null && !reasoningEffort.isEmpty()) {
            body.addProperty("reasoning_effort", reasoningEffort);
        }

        List<String> stopSeq = config.getStopSequences();
        if (!stopSeq.isEmpty()) {
            JsonArray stops = new JsonArray();
            for (String s : stopSeq) {
                stops.add(s);
            }
            body.add("stop", stops);
        }

        return GSON.toJson(body);
    }

    private HttpRequest buildRequest(String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(config.getEndpoint()))
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .timeout(config.getTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    // ---- Response parsing --------------------------------------------------------

    /// Extracts `choices[0].message.content` from a non-streaming response.
    private static String parseNonStreamingResponse(String responseBody) throws LlmException {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new LlmException("Empty choices in response", 0);
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null) {
                throw new LlmException("Missing message in response", 0);
            }
            JsonElement content = message.get("content");
            return content != null ? content.getAsString() : "";
        } catch (JsonParseException | IllegalStateException e) {
            throw new LlmException("Failed to parse response: " + e.getMessage(), 0, e);
        }
    }

    /// Parses an SSE stream from the given InputStream, delivering tokens to [callback].
    private static void parseSseStream(InputStream inputStream, LlmStreamCallback callback) throws IOException {
        StringBuilder fullResponse = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) {
                    continue;
                }

                String data = line.substring(6).trim(); // strip "data: " prefix

                if (data.equals("[DONE]")) {
                    callback.onComplete(fullResponse.toString());
                    return;
                }

                try {
                    JsonObject json = JsonParser.parseString(data).getAsJsonObject();
                    JsonArray choices = json.getAsJsonArray("choices");
                    if (choices != null && !choices.isEmpty()) {
                        JsonObject choice = choices.get(0).getAsJsonObject();
                        JsonObject delta = choice.getAsJsonObject("delta");
                        if (delta != null) {
                            JsonElement content = delta.get("content");
                            if (content != null && !content.isJsonNull()) {
                                String token = content.getAsString();
                                if (!token.isEmpty()) {
                                    fullResponse.append(token);
                                    callback.onToken(token);
                                }
                            }
                        }
                    }
                } catch (JsonParseException e) {
                    // Skip malformed SSE data lines silently; they are not fatal.
                }
            }

            // Stream ended without [DONE] — treat as complete with what we have.
            callback.onComplete(fullResponse.toString());
        }
    }

    // ---- Error handling ----------------------------------------------------------

    /// Reads all remaining bytes from an InputStream into a String.
    private static String readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = inputStream.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /// Maps an HTTP status code and optional response body to an [`LlmException`].
    private static LlmException mapHttpError(int status, @Nullable String body) {
        String detail = (body != null && !body.isEmpty()) ? " — " + body : "";

        switch (status) {
            case 401:
                return new LlmException("Authentication error" + detail, status);
            case 429:
                return new LlmException("Rate limit exceeded" + detail, status);
            default:
                if (status >= 500) {
                    return new LlmException("Server error" + detail, status);
                } else {
                    return new LlmException("HTTP error " + status + detail, status);
                }
        }
    }
}
