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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for {@link AiModelDiscoveryService}.
public final class AiModelDiscoveryServiceTest {

    private HttpServer server;

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /// Starts an in-process HTTP server on a random port handling /v1/models.
    private int startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/models", handler);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        return server.getAddress().getPort();
    }

    /// The discovery client must route through the process-wide ProxySelector HMCL installs
    /// at startup — otherwise model-list probing bypasses the user's proxy. While no proxy
    /// credentials were pushed, NO authenticator may be attached (an authenticator-carrying
    /// client fails bare 401s with IOException instead of surfacing them — see
    /// testNon200ReturnsEmpty, whose server answers 401 without a WWW-Authenticate header).
    @Test
    public void testHttpClientHonoursProxyWithoutDefaultAuthenticator() {
        AiModelDiscoveryService service = new AiModelDiscoveryService();
        assertTrue(service.httpClient().proxy().isPresent(),
                "discovery HttpClient must be built with .proxy(ProxySelector.getDefault())");
        assertSame(java.net.ProxySelector.getDefault(), service.httpClient().proxy().get(),
                "the ProxySelector must be the process-wide default");
        assertTrue(service.httpClient().authenticator().isEmpty(),
                "no authenticator may be attached while no proxy credentials were pushed");
    }

    /// A proxy authenticator pushed by HMCL before the service is constructed must reach the
    /// discovery client (build-time capture — the service is created per settings-page use).
    @Test
    public void testHttpClientPicksUpPushedProxyAuthenticator() {
        java.net.Authenticator pushed = new java.net.Authenticator() {
        };
        org.jackhuang.hmcl.ai.net.ProxyAuthenticatorHolder.set(pushed);
        try {
            AiModelDiscoveryService service = new AiModelDiscoveryService();
            assertSame(pushed, service.httpClient().authenticator().orElseThrow(),
                    "the pushed authenticator must be attached for proxy 407 challenges");
        } finally {
            org.jackhuang.hmcl.ai.net.ProxyAuthenticatorHolder.set(null);
        }
    }

    /// Verifies parsing of a standard OpenAI /v1/models response.
    @Test
    public void testParseStandardModelsResponse() throws IOException, InterruptedException {
        String responseBody = "{"
                + "\"object\": \"list\","
                + "\"data\": ["
                + "  {\"id\": \"gpt-4o\", \"object\": \"model\", \"created\": 1, \"owned_by\": \"openai\"},"
                + "  {\"id\": \"gpt-4o-mini\", \"object\": \"model\", \"created\": 2, \"owned_by\": \"openai\"},"
                + "  {\"id\": \"gpt-4.1\", \"object\": \"model\", \"created\": 3, \"owned_by\": \"openai\"}"
                + "]}";

        int port = startServer(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        AiProviderProfile profile = createOpenAiProfile(port);
        AiModelDiscoveryService service = new AiModelDiscoveryService();
        List<String> models = service.discoverModels(profile);

        assertEquals(3, models.size());
        assertTrue(models.contains("gpt-4o"));
        assertTrue(models.contains("gpt-4o-mini"));
        assertTrue(models.contains("gpt-4.1"));
    }

    /// Verifies that non-200 HTTP status returns empty list.
    @Test
    public void testNon200ReturnsEmpty() throws IOException, InterruptedException {
        int port = startServer(exchange -> {
            byte[] bytes = "{\"error\": \"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        AiProviderProfile profile = createOpenAiProfile(port);
        AiModelDiscoveryService service = new AiModelDiscoveryService();
        List<String> models = service.discoverModels(profile);

        assertTrue(models.isEmpty());
    }

    /// Anthropic family returns empty list without HTTP call.
    @Test
    public void testAnthropicReturnsEmptyWithoutHttp() throws IOException, InterruptedException {
        AiProviderProfile profile = new AiProviderProfile();
        profile.setProtocolFamily(AiProtocolFamily.ANTHROPIC.getId());
        profile.setEndpoint("https://api.anthropic.com/v1/messages");
        profile.setApiKey("test-key");

        AiModelDiscoveryService service = new AiModelDiscoveryService();
        List<String> models = service.discoverModels(profile);

        assertTrue(models.isEmpty());
    }

    /// REST API family returns empty list.
    @Test
    public void testRestApiReturnsEmpty() throws IOException, InterruptedException {
        AiProviderProfile profile = new AiProviderProfile();
        profile.setProtocolFamily(AiProtocolFamily.RESTAPI.getId());

        AiModelDiscoveryService service = new AiModelDiscoveryService();
        List<String> models = service.discoverModels(profile);

        assertTrue(models.isEmpty());
    }

    /// Empty models response data returns empty list.
    @Test
    public void testEmptyDataArray() {
        AiModelDiscoveryService service = new AiModelDiscoveryService();
        List<String> models = service.parseModelsResponse(
                "{\"object\": \"list\", \"data\": []}");
        assertTrue(models.isEmpty());
    }

    /// Malformed JSON returns empty list without throwing.
    @Test
    public void testMalformedJsonReturnsEmpty() {
        AiModelDiscoveryService service = new AiModelDiscoveryService();
        List<String> models = service.parseModelsResponse("not json at all");
        assertTrue(models.isEmpty());
    }

    /// Missing data field returns empty list.
    @Test
    public void testMissingDataFieldReturnsEmpty() {
        AiModelDiscoveryService service = new AiModelDiscoveryService();
        List<String> models = service.parseModelsResponse("{\"object\": \"list\"}");
        assertTrue(models.isEmpty());
    }

    /// "data" present but not an array (e.g. a tampered/hostile custom-endpoint response) must not
    /// throw — Gson's getAsJsonArray() throws IllegalStateException for this shape.
    @Test
    public void testDataNotAnArrayReturnsEmptyWithoutThrowing() {
        AiModelDiscoveryService service = new AiModelDiscoveryService();
        List<String> models = service.parseModelsResponse("{\"data\": \"not-an-array\"}");
        assertTrue(models.isEmpty());
    }

    /// An array entry that isn't a JSON object (e.g. a bare string) must be skipped, not throw.
    @Test
    public void testDataEntryNotAnObjectIsSkippedWithoutThrowing() {
        AiModelDiscoveryService service = new AiModelDiscoveryService();
        List<String> models = service.parseModelsResponse(
                "{\"data\": [\"just-a-string\", {\"id\": \"gpt-4o\"}]}");
        assertEquals(List.of("gpt-4o"), models);
    }

    /// An "id" that isn't a JSON primitive (e.g. a nested object) must be skipped, not throw —
    /// Gson's getAsString() throws UnsupportedOperationException for a non-primitive element.
    @Test
    public void testIdNotAPrimitiveIsSkippedWithoutThrowing() {
        AiModelDiscoveryService service = new AiModelDiscoveryService();
        List<String> models = service.parseModelsResponse(
                "{\"data\": [{\"id\": {\"nested\": true}}, {\"id\": \"gpt-4o\"}]}");
        assertEquals(List.of("gpt-4o"), models);
    }

    /// deriveModelsUrl strips chat/completions suffix.
    @Test
    public void testDeriveModelsUrlFromChatCompletions() {
        AiModelDiscoveryService service = new AiModelDiscoveryService();
        String result = service.deriveModelsUrl(
                "https://api.openai.com/v1/chat/completions");
        assertEquals("https://api.openai.com/v1/models", result);
    }

    /// deriveModelsUrl strips messages suffix for Anthropic.
    @Test
    public void testDeriveModelsUrlFromMessages() {
        AiModelDiscoveryService service = new AiModelDiscoveryService();
        String result = service.deriveModelsUrl(
                "https://api.anthropic.com/v1/messages");
        assertEquals("https://api.anthropic.com/v1/models", result);
    }

    /// deriveModelsUrl returns null for unrecognized paths.
    @Test
    public void testDeriveModelsUrlReturnsNullForUnknownPath() {
        AiModelDiscoveryService service = new AiModelDiscoveryService();
        String result = service.deriveModelsUrl("https://example.com/custom/path");
        assertNull(result);
    }

    /// deriveModelsUrl handles /v1 base path.
    @Test
    public void testDeriveModelsUrlFromV1Base() {
        AiModelDiscoveryService service = new AiModelDiscoveryService();
        String result = service.deriveModelsUrl("https://api.openai.com/v1");
        assertEquals("https://api.openai.com/v1/models", result);
    }

    private static AiProviderProfile createOpenAiProfile(int port) {
        AiProviderProfile profile = new AiProviderProfile();
        profile.setProtocolFamily(AiProtocolFamily.OPENAI_COMPLETIONS.getId());
        profile.setEndpoint("http://localhost:" + port + "/v1/chat/completions");
        profile.setApiKey("test-key");
        return profile;
    }
}
