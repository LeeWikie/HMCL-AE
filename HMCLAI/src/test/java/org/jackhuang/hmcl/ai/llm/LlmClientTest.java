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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.ai.LlmConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for [`LlmClient`] using Java's built-in [`HttpServer`] for mock HTTP responses.
///
/// Each test starts an in-process HTTP server on a random port and creates an [`LlmClient`]
/// pointing to it, verifying the client's behaviour without any external network calls.
public final class LlmClientTest {

    private HttpServer server;

    /// Starts an in-process HTTP server on a random port using the given handler
    /// for the `/v1/chat/completions` context path.
    private int startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", handler);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        return server.getAddress().getPort();
    }

    /// Creates an [`LlmClient`] pointed at the mock server on the given port.
    private LlmClient createClient(int port) {
        LlmConfig config = new LlmConfig(
                "http://localhost:" + port + "/v1/chat/completions",
                "test-api-key",
                "gpt-4o-mini",
                4096,
                0.7d,
                Duration.ofSeconds(15)
        );
        return new LlmClient(config);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /// Verifies that a non-streaming request with a valid JSON response returns
    /// the extracted content string.
    @Test
    @Timeout(10)
    public void testSendMessageSuccess() throws Exception {
        int port = startServer(exchange -> {
            String body = "{\"choices\":[{\"message\":{\"content\":\"Hello, world!\"}}]}";
            sendJson(exchange, 200, body);
        });

        LlmClient client = createClient(port);
        List<LlmMessage> messages = List.of(new LlmMessage("user", "Hi"));

        String response = client.sendMessage(messages).get(5, TimeUnit.SECONDS);
        assertEquals("Hello, world!", response);
    }

    /// Verifies that a 401 Unauthorized response maps to "Authentication error".
    @Test
    @Timeout(10)
    public void testSendMessageError401() throws Exception {
        int port = startServer(exchange -> {
            String body = "{\"error\":{\"message\":\"Invalid API key\"}}";
            sendJson(exchange, 401, body);
        });

        LlmClient client = createClient(port);
        List<LlmMessage> messages = List.of(new LlmMessage("user", "Hi"));

        ExecutionException ex = assertThrows(ExecutionException.class, () ->
                client.sendMessage(messages).get(5, TimeUnit.SECONDS)
        );
        LlmException cause = unwrapLlmException(ex);
        assertNotNull(cause, "Should find LlmException in cause chain");
        assertTrue(cause.getMessage().contains("Authentication error"),
                "Message should contain 'Authentication error', got: " + cause.getMessage());
        assertEquals(401, cause.getStatusCode());
    }

    /// Verifies that a 429 Too Many Requests response maps to "Rate limit exceeded".
    @Test
    @Timeout(10)
    public void testSendMessageError429() throws Exception {
        int port = startServer(exchange -> {
            String body = "{\"error\":{\"message\":\"Too many requests\"}}";
            sendJson(exchange, 429, body);
        });

        LlmClient client = createClient(port);
        List<LlmMessage> messages = List.of(new LlmMessage("user", "Hi"));

        ExecutionException ex = assertThrows(ExecutionException.class, () ->
                client.sendMessage(messages).get(5, TimeUnit.SECONDS)
        );
        LlmException cause = unwrapLlmException(ex);
        assertNotNull(cause, "Should find LlmException in cause chain");
        assertTrue(cause.getMessage().contains("Rate limit exceeded"),
                "Message should contain 'Rate limit exceeded', got: " + cause.getMessage());
        assertEquals(429, cause.getStatusCode());
    }

    /// Verifies that streaming SSE responses deliver tokens via the callback
    /// and complete with the full concatenated response.
    @Test
    @Timeout(10)
    public void testSendMessageStreaming() throws Exception {
        int port = startServer(exchange -> {
            byte[] sseBody = (
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n" +
                    "\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\", \"}}]}\n" +
                    "\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"world!\"}}]}\n" +
                    "\n" +
                    "data: [DONE]\n"
            ).getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, sseBody.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(sseBody);
            }
        });

        LlmClient client = createClient(port);
        List<LlmMessage> messages = List.of(new LlmMessage("user", "Hi"));

        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder tokens = new StringBuilder();
        String[] fullResponse = {null};
        LlmException[] error = {null};

        client.sendMessageStreaming(messages, new LlmStreamCallback() {
            @Override
            public void onToken(String token) {
                tokens.append(token);
            }

            @Override
            public void onComplete(String response) {
                fullResponse[0] = response;
                latch.countDown();
            }

            @Override
            public void onError(LlmException e) {
                error[0] = e;
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Callback should complete within timeout");
        assertNull(error[0], "Should not have an error: " + (error[0] != null ? error[0].getMessage() : ""));
        assertEquals("Hello, world!", tokens.toString(),
                "Tokens received via onToken should concatenate correctly");
        assertEquals("Hello, world!", fullResponse[0],
                "Full response from onComplete should match concatenated tokens");
    }

    /// Walks the cause chain of an [`ExecutionException`] to find the wrapped [`LlmException`].
    private static LlmException unwrapLlmException(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof LlmException) {
                return (LlmException) current;
            }
            current = current.getCause();
        }
        return null;
    }

    /// Sends a JSON response with the given status code and body.
    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
