/*
 * Hello Minecraft! Launcher - Agent Experience
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
package org.jackhuang.hmcl.ai.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Test-only fake MCP stdio server, launched as a real child process by
/// {@code McpClientManagerTest} (never invoked directly from Java) to prove that
/// {@link AiMcpServerConfig}'s {@code args}/{@code env} really reach the OS-level subprocess
/// {@link McpClientManager} spawns — not just that {@code ProcessBuilder} was *called* with the
/// right values.
///
/// Speaks just enough line-delimited JSON-RPC (see langchain4j-mcp's
/// {@code dev.langchain4j.mcp.transport.stdio.JsonRpcIoHandler}: one JSON object per line, no
/// framing) for {@code DefaultMcpClient}'s constructor handshake and a subsequent
/// {@code listTools()} call to both succeed: every incoming line that has an {@code "id"} gets an
/// empty (or, for {@code "tools/list"}, empty-tools-list) result reply; everything else
/// (notifications) is ignored.
///
/// Before entering that loop, it records exactly what it was launched with — its own argv (its
/// {@code args} minus {@code args[0]}, the marker file path) and a couple of environment variables
/// — as JSON into the marker file at {@code args[0]}. That marker file is the actual assertion
/// target in the test.
public final class FakeStdioMcpServerMain {

    private FakeStdioMcpServerMain() {
    }

    public static void main(String[] args) throws IOException {
        Path markerFile = Path.of(args[0]);
        List<String> extraArgs = args.length > 1 ? List.of(args).subList(1, args.length) : List.of();

        JsonObject marker = new JsonObject();
        JsonArray argsArray = new JsonArray();
        extraArgs.forEach(argsArray::add);
        marker.add("args", argsArray);
        JsonObject env = new JsonObject();
        env.addProperty("MCP_TEST_KEY", nullToEmpty(System.getenv("MCP_TEST_KEY")));
        env.addProperty("MCP_TEST_OTHER", nullToEmpty(System.getenv("MCP_TEST_OTHER")));
        marker.add("env", env);
        Files.writeString(markerFile, marker.toString(), StandardCharsets.UTF_8);

        PrintStream out = System.out;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                respond(out, line);
            }
        }
    }

    private static void respond(PrintStream out, String line) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(line);
        } catch (RuntimeException ignored) {
            return; // malformed input: mirror the real handler's leniency and just drop it
        }
        if (!parsed.isJsonObject()) {
            return;
        }
        JsonObject request = parsed.getAsJsonObject();
        if (!request.has("id")) {
            return; // a notification (e.g. "notifications/initialized"): no response expected
        }

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", request.get("id"));
        JsonObject result = new JsonObject();
        if (request.has("method") && "tools/list".equals(request.get("method").getAsString())) {
            result.add("tools", new JsonArray());
        }
        response.add("result", result);
        out.println(response);
        out.flush();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
