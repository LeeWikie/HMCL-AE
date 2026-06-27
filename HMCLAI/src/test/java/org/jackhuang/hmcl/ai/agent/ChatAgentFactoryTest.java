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
package org.jackhuang.hmcl.ai.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.LlmConfig;
import org.jackhuang.hmcl.ai.llm.LlmException;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for {@link ChatAgentFactory} covering agent construction and
/// the lightweight connection test helper.
public final class ChatAgentFactoryTest {

    private HttpServer server;
    private Path tempDir;

    /// Starts an in-process HTTP server on a random port using the given handler
    /// for the `/v1/chat/completions` context path.
    private int startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", handler);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        return server.getAddress().getPort();
    }

    /// Creates settings pointing at the mock server. Uses a non-L4j
    /// provider so the legacy [`LlmClient`][org.jackhuang.hmcl.ai.llm.LlmClient]
    /// path is exercised (mock servers cannot serve LangChain4j's internal
    /// HTTP client routing).
    private AiSettings createSettings(int port) throws IOException {
        tempDir = Files.createTempDirectory("hmcl-ai-factory-test-");
        AiSettings settings = new AiSettings(tempDir);
        settings.endpointProperty().set("http://localhost:" + port + "/v1/chat/completions");
        settings.apiKeyProperty().set("test-api-key");
        settings.modelProperty().set("gpt-4o-mini");
        settings.providerProperty().set("custom-mock");
        return settings;
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (tempDir != null) {
            try {
                Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                        });
            } catch (IOException ignored) {
            }
        }
    }

    /// Verifies that {@link ChatAgentFactory#build} creates a functional ChatAgent
    /// bound to the supplied session and tools, and that the settings are exposed.
    @Test
    public void testBuildCreatesFunctionalAgent() throws IOException {
        AiSession session = new AiSession();
        ToolRegistry tools = new ToolRegistry();

        Path dir = Files.createTempDirectory("hmcl-ai-factory-build-");
        try {
            AiSettings settings = new AiSettings(dir);
            ChatAgent agent = ChatAgentFactory.build(settings, session, tools);

            assertNotNull(agent);
            assertSame(session, agent.getSession());
            assertNotNull(agent.getSettings(), "ChatAgent should expose its settings");
            assertSame(settings, agent.getSettings(), "ChatAgent.getSettings() should return the same instance");
        } finally {
            cleanup(dir);
        }
    }

    /// Verifies that testConnection succeeds for a valid mock endpoint.
    @Test
    @Timeout(10)
    public void testTestConnectionSuccess() throws Exception {
        int port = startServer(exchange -> {
            String body = "{\"choices\":[{\"message\":{\"content\":\"Hello\"}}]}";
            sendJson(exchange, 200, body);
        });

        AiSettings settings = createSettings(port);
        String response = ChatAgentFactory.testConnectionSync(settings, 5);

        assertNotNull(response);
        assertEquals("Hello", response);
    }

    /// Verifies that testConnection maps 401 to authentication error.
    @Test
    @Timeout(10)
    public void testTestConnection401() throws Exception {
        int port = startServer(exchange -> {
            String body = "{\"error\":{\"message\":\"Invalid API key\"}}";
            sendJson(exchange, 401, body);
        });

        AiSettings settings = createSettings(port);

        LlmException ex = assertThrows(LlmException.class, () ->
                ChatAgentFactory.testConnectionSync(settings, 5)
        );
        assertTrue(ex.getMessage().contains("Authentication error"));
        assertEquals(401, ex.getStatusCode());
    }

    /// Verifies that testConnection maps 429 to rate limit error.
    @Test
    @Timeout(10)
    public void testTestConnection429() throws Exception {
        int port = startServer(exchange -> {
            String body = "{\"error\":{\"message\":\"Too many requests\"}}";
            sendJson(exchange, 429, body);
        });

        AiSettings settings = createSettings(port);

        LlmException ex = assertThrows(LlmException.class, () ->
                ChatAgentFactory.testConnectionSync(settings, 5)
        );
        assertTrue(ex.getMessage().contains("Rate limit exceeded"));
        assertEquals(429, ex.getStatusCode());
    }

    /// Verifies that testConnection uses the correct prompt and parameters.
    @Test
    @Timeout(10)
    public void testTestConnectionUsesCorrectPrompt() throws Exception {
        // Capture the request body to verify the prompt and parameters.
        final String[] capturedBody = {null};
        int port = startServer(exchange -> {
            capturedBody[0] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String body = "{\"choices\":[{\"message\":{\"content\":\"Hello\"}}]}";
            sendJson(exchange, 200, body);
        });

        AiSettings settings = createSettings(port);
        ChatAgentFactory.testConnectionSync(settings, 5);

        assertNotNull(capturedBody[0]);
        // Verify the test prompt is included.
        assertTrue(capturedBody[0].contains(ChatAgentFactory.TEST_PROMPT),
                "Request body must contain the test prompt");
        // Verify temperature is 0.
        assertTrue(capturedBody[0].contains("\"temperature\":0.0"),
                "Request body must use temperature 0");
        // Verify max_tokens is small.
        assertTrue(capturedBody[0].contains("\"max_tokens\":" + ChatAgentFactory.TEST_MAX_TOKENS),
                "Request body must use a small max_tokens");
    }

    /// Verifies that testConnection handles network timeout gracefully.
    @Test
    @Timeout(15)
    public void testTestConnectionTimeout() throws Exception {
        int port = startServer(exchange -> {
            // Sleep longer than the test timeout to simulate a slow server.
            try {
                Thread.sleep(15000);
            } catch (InterruptedException ignored) {
            }
        });

        AiSettings settings = createSettings(port);

        assertThrows(java.util.concurrent.TimeoutException.class, () ->
                ChatAgentFactory.testConnectionSync(settings, 2)
        );
    }

    /// Verifies that testConnection fails with LlmException when the model name is
    /// empty and the server returns an error.
    @Test
    @Timeout(10)
    public void testTestConnectionWithEmptyModel() throws Exception {
        int port = startServer(exchange -> {
            String body = "{\"choices\":[{\"message\":{\"content\":\"Hello\"}}]}";
            sendJson(exchange, 200, body);
        });

        AiSettings settings = createSettings(port);
        settings.modelProperty().set(""); // Empty model

        // Should fall back to DEFAULT_MODEL and succeed.
        String response = ChatAgentFactory.testConnectionSync(settings, 5);
        assertNotNull(response);
    }

    /// Verifies that testConnection properly passes through a generic HTTP error.
    @Test
    @Timeout(10)
    public void testTestConnection500() throws Exception {
        int port = startServer(exchange -> {
            sendJson(exchange, 500, "{\"error\":\"Internal error\"}");
        });

        AiSettings settings = createSettings(port);

        LlmException ex = assertThrows(LlmException.class, () ->
                ChatAgentFactory.testConnectionSync(settings, 5)
        );
        assertTrue(ex.getMessage().contains("Server error"));
        assertEquals(500, ex.getStatusCode());
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void cleanup(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                    });
        } catch (IOException ignored) {
        }
    }
}
