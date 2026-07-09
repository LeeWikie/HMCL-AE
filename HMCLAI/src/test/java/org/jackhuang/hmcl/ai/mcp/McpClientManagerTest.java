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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// End-to-end proof that {@link McpClientManager#connectAndRegister} really launches the stdio
/// child process with the {@link AiMcpServerConfig#getArgs() args} and
/// {@link AiMcpServerConfig#getEnv() env} configured on it — not merely that some Java object
/// (e.g. a mocked {@code ProcessBuilder}) was built correctly.
///
/// {@link FakeStdioMcpServerMain} is launched as an actual subprocess (the real JVM binary this
/// test itself runs under) and, before doing anything else, writes what it was launched with —
/// its own argv and a couple of environment variables it reads via {@code System.getenv} — to a
/// marker file. That marker file, read back from the *outside* after the round trip, is the actual
/// assertion target: it can only contain the expected values if the OS-level process truly
/// received them.
class McpClientManagerTest {

    private final List<Path> markerFiles = new ArrayList<>();
    private final List<McpClientManager> managers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (McpClientManager manager : managers) {
            for (String serverId : new ArrayList<>(manager.getClients().keySet())) {
                manager.disconnect(serverId);
            }
        }
        for (Path marker : markerFiles) {
            try {
                Files.deleteIfExists(marker);
            } catch (IOException ignored) {
                // best-effort cleanup only
            }
        }
    }

    @Test
    @Timeout(30)
    void connectAndRegisterPassesConfiguredArgsAndEnvToTheRealChildProcess() throws IOException {
        Path markerFile = newMarkerFile();
        String fqcn = FakeStdioMcpServerMain.class.getName();

        AiMcpServerConfig config = new AiMcpServerConfig();
        config.setTransport("stdio");
        config.setCommand(javaExecutable());
        config.setArgs(List.of(
                "-cp", testClasspath(),
                fqcn,
                markerFile.toString(), // FakeStdioMcpServerMain's args[0]: where to write the marker
                "first-arg",           // its args[1]
                "--flag-with-a-space in-one-token")); // its args[2]: one argv entry despite the space
        Map<String, String> env = new LinkedHashMap<>();
        env.put("MCP_TEST_KEY", "hello-mcp-env-42");
        env.put("MCP_TEST_OTHER", "second-value");
        config.setEnv(env);

        McpClientManager manager = new McpClientManager(new ToolRegistry());
        managers.add(manager);

        boolean connected = manager.connectAndRegister(config);
        assertTrue(connected, "the fake stdio MCP server should have started and completed the "
                + "MCP initialize handshake");

        JsonObject marker = readMarker(markerFile);
        List<String> observedArgs = new ArrayList<>();
        marker.getAsJsonArray("args").forEach(e -> observedArgs.add(e.getAsString()));
        // FakeStdioMcpServerMain reports its OWN args[1:] (args[0] is the marker file path it was
        // told to write to), so this is exactly the configured args minus the bootstrap/marker
        // entries above — proof the child process's real argv matches what was configured,
        // including the embedded space staying inside a single argv entry rather than being split.
        assertEquals(List.of("first-arg", "--flag-with-a-space in-one-token"), observedArgs,
                "the child process's own argv must match exactly what "
                        + "AiMcpServerConfig#getArgs() was configured with");

        JsonObject observedEnv = marker.getAsJsonObject("env");
        assertEquals("hello-mcp-env-42", observedEnv.get("MCP_TEST_KEY").getAsString());
        assertEquals("second-value", observedEnv.get("MCP_TEST_OTHER").getAsString());
    }

    @Test
    void buildCommandLineUsesArgsListVerbatimWhenPresent() {
        List<String> commandLine = McpClientManager.buildCommandLine(
                "npx", List.of("-y", "@my/mcp-server", "--flag with spaces"));

        assertEquals(List.of("npx", "-y", "@my/mcp-server", "--flag with spaces"), commandLine,
                "when args are configured, they must be used verbatim as separate argv entries — "
                        + "never re-split on whitespace");
    }

    @Test
    void buildCommandLineFallsBackToWhitespaceSplitOnlyWhenArgsIsEmpty() {
        List<String> commandLine = McpClientManager.buildCommandLine("python3 server.py --flag", List.of());

        assertEquals(List.of("python3", "server.py", "--flag"), commandLine,
                "backward compatibility: a config saved before args/env existed had the whole "
                        + "command line crammed into the command string");
    }

    private Path newMarkerFile() throws IOException {
        Path marker = Files.createTempFile("mcp-client-manager-test-", ".json");
        markerFiles.add(marker);
        return marker;
    }

    private static JsonObject readMarker(Path markerFile) throws IOException {
        String json = Files.readString(markerFile);
        JsonElement parsed = JsonParser.parseString(json);
        return parsed.getAsJsonObject();
    }

    private static String javaExecutable() {
        String javaHome = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(javaHome, "bin", windows ? "java.exe" : "java").toString();
    }

    private static String testClasspath() {
        return System.getProperty("java.class.path");
    }
}
