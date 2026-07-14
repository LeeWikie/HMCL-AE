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
package org.jackhuang.hmcl.ai.kb;

import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// {@link KbSearchTool} against a stub HTTP server (no real network): the enabled/query/mode/endpoint
/// guards, the happy path (fetch → fenced content, hitting {@code /tools/searchHybrid} with the query),
/// the empty-content → "no results" branch, and a 500 → graceful failure.
public final class KbSearchToolTest {

    private HttpServer server;
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile String lastPath = "";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath = exchange.getRequestURI().toString();
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, b.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AiKbConfig remoteConfig() {
        AiKbConfig c = new AiKbConfig();
        c.setEnabled(true);
        c.setSourceMode(KbSourceMode.REMOTE_HTTP);
        c.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
        return c;
    }

    private static ToolResult run(AiKbConfig c, String query) {
        return new KbSearchTool(c).execute(Map.<String, Object>of("query", query));
    }

    @Test
    void disabledFails() {
        AiKbConfig c = remoteConfig();
        c.setEnabled(false);
        assertFalse(run(c, "creeper").isSuccess(), "disabled KB must not run");
    }

    @Test
    void emptyQueryFails() {
        assertFalse(run(remoteConfig(), "   ").isSuccess(), "blank query must fail");
    }

    @Test
    void localModeReportsNotAvailable() {
        AiKbConfig c = remoteConfig();
        c.setSourceMode(KbSourceMode.LOCAL_INDEX);
        c.setLocalIndexPath("whatever");
        assertFalse(run(c, "creeper").isSuccess(), "LOCAL_INDEX is not wired yet → explicit failure");
    }

    @Test
    void blankEndpointFails() {
        AiKbConfig c = remoteConfig();
        c.setEndpoint("");
        assertFalse(run(c, "creeper").isSuccess(), "no endpoint → fail before any request");
    }

    @Test
    void hitReturnsFencedContentAndCallsHybridRouteWithQuery() {
        body = "{\"content\":\"## 机械动力\\n一个模组\",\"sources\":[],\"confidence\":\"high\","
                + "\"related_queries\":[],\"version\":\"all\"}";
        ToolResult r = run(remoteConfig(), "机械动力");
        assertTrue(r.isSuccess(), "200 + content → success");
        assertTrue(r.getOutput().contains("机械动力"), "server content must pass through");
        assertTrue(r.getOutput().contains("knowledge_base_results"), "results must be fenced as untrusted");
        assertTrue(lastPath.contains("/tools/searchHybrid"), "must hit the hybrid route: " + lastPath);
        assertTrue(lastPath.contains("query="), "must send the query: " + lastPath);
    }

    @Test
    void emptyContentBecomesNoResults() {
        body = "{\"content\":\"未找到相关结果。\",\"sources\":[],\"confidence\":\"low\","
                + "\"related_queries\":[],\"version\":\"all\"}";
        ToolResult r = run(remoteConfig(), "zzznotathing");
        assertTrue(r.isSuccess(), "a valid empty result is still a success, not an error");
        assertTrue(r.getOutput().toLowerCase().contains("no relevant knowledge"),
                "empty/未找到 content → the no-results message");
    }

    @Test
    void serverErrorIsGracefulFailure() {
        status = 500;
        body = "boom";
        ToolResult r = run(remoteConfig(), "creeper");
        assertFalse(r.isSuccess(), "HTTP 500 → failure, not a crash");
    }
}
