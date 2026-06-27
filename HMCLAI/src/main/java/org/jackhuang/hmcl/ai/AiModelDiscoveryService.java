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
package org.jackhuang.hmcl.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// Discovers available model ids from a provider endpoint.
///
/// For OpenAI-compatible protocol families, a `GET /v1/models` request is
/// issued against the profile's normalized endpoint. The response is parsed
/// for model ids in the standard `data[].id` JSON shape.
///
/// For protocol families that do not support model listing (Anthropic, REST API),
/// the service returns an empty list without making any HTTP call.
///
/// ## Thread safety
///
/// Instances are stateless and thread-safe. The internal {@link HttpClient}
/// is shared across calls.
@NotNullByDefault
public final class AiModelDiscoveryService {

    private static final Gson GSON = new Gson();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /// The chat-completions path suffix used when deriving the base URL.
    private static final String CHAT_PATH = "/chat/completions";

    /// The messages path suffix used by Anthropic.
    private static final String MESSAGES_PATH = "/messages";

    private final HttpClient httpClient;

    /// Creates a discovery service with a default HTTP client.
    public AiModelDiscoveryService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    /// Discovers model ids for the given provider profile.
    ///
    /// Only OpenAI-compatible protocol families trigger an HTTP request.
    /// Other families return an empty list immediately.
    ///
    /// @param profile the provider profile whose endpoint to query
    /// @return the list of discovered model ids (never `null`)
    /// @throws IOException          if an I/O error occurs during the HTTP call
    /// @throws InterruptedException if the HTTP call is interrupted
    public List<String> discoverModels(AiProviderProfile profile)
            throws IOException, InterruptedException {
        AiProtocolFamily family = AiProtocolFamily.fromId(profile.getProtocolFamily());
        if (family == null || !family.isOpenaiCompatible()) {
            return Collections.emptyList();
        }

        String normalizedEndpoint = AiEndpointNormalizer.normalize(
                profile.getEndpoint(), profile.getProtocolFamily());
        if (normalizedEndpoint == null) {
            return Collections.emptyList();
        }

        String modelsUrl = deriveModelsUrl(normalizedEndpoint);
        if (modelsUrl == null) {
            return Collections.emptyList();
        }

        HttpRequest request = buildRequest(modelsUrl, profile.getApiKey());
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            return Collections.emptyList();
        }

        return parseModelsResponse(response.body());
    }

    /// Builds the `GET /v1/models` URL from a normalized chat endpoint.
    ///
    /// Strips the known chat path suffix and appends `/models`. If the
    /// endpoint does not contain a recognised suffix, no derivation is
    /// possible and `null` is returned.
    @Nullable
    String deriveModelsUrl(String normalizedEndpoint) {
        String base = normalizedEndpoint;

        if (base.endsWith(CHAT_PATH)) {
            base = base.substring(0, base.length() - CHAT_PATH.length());
        } else if (base.endsWith(MESSAGES_PATH)) {
            base = base.substring(0, base.length() - MESSAGES_PATH.length());
        }

        if (!base.endsWith("/v1")) {
            return null;
        }

        return stripTrailingSlash(base) + "/models";
    }

    private static String stripTrailingSlash(String s) {
        if (s.endsWith("/")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    /// Builds an HTTP GET request to the models URL, including the API key
    /// in the `Authorization` header if present.
    private HttpRequest buildRequest(String modelsUrl, String apiKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(modelsUrl))
                .timeout(REQUEST_TIMEOUT)
                .GET();

        if (apiKey != null && !apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        return builder.build();
    }

    /// Parses the OpenAI `/v1/models` JSON response for model ids.
    ///
    /// Expected shape:
    /// ```json
    /// {"object": "list", "data": [{"id": "gpt-4o", ...}, ...]}
    /// ```
    ///
    /// @param responseBody the raw JSON response body
    /// @return the parsed model id list (empty on parse failure)
    List<String> parseModelsResponse(String responseBody) {
        try {
            JsonObject root = GSON.fromJson(responseBody, JsonObject.class);
            if (root == null || !root.has("data")) {
                return Collections.emptyList();
            }
            JsonArray data = root.getAsJsonArray("data");
            List<String> models = new ArrayList<>(data.size());
            for (int i = 0; i < data.size(); i++) {
                JsonObject entry = data.get(i).getAsJsonObject();
                if (entry.has("id")) {
                    models.add(entry.get("id").getAsString());
                }
            }
            return models;
        } catch (JsonParseException | ClassCastException e) {
            return Collections.emptyList();
        }
    }
}
