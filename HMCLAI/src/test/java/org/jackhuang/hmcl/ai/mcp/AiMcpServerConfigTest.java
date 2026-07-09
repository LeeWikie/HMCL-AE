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

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Round-trip and backward-compatibility coverage for the {@code args}/{@code env} fields added to
/// {@link AiMcpServerConfig} so {@link McpClientManager}'s stdio transport can launch a server with
/// real argv/environment instead of naively splitting one command string on whitespace.
class AiMcpServerConfigTest {

    private static final Gson GSON = new Gson();

    @Test
    void roundTripThroughJsonPreservesArgsAndEnv() {
        AiMcpServerConfig original = new AiMcpServerConfig();
        original.setDisplayName("My Server");
        original.setTransport("stdio");
        original.setCommand("npx");
        original.setArgs(List.of("-y", "@my/mcp-server", "--flag"));
        Map<String, String> env = new LinkedHashMap<>();
        env.put("API_KEY", "secret-123");
        env.put("MODE", "prod");
        original.setEnv(env);
        original.setEnabled(true);
        original.setAutoConnect(false);
        original.setAllowedTools(List.of("toolA", "toolB"));
        original.setExposeResourcesAsTools(true);

        String json = GSON.toJson(original);
        AiMcpServerConfig reloaded = GSON.fromJson(json, AiMcpServerConfig.class);

        assertEquals(original.getId(), reloaded.getId());
        assertEquals(original.getDisplayName(), reloaded.getDisplayName());
        assertEquals(original.getTransport(), reloaded.getTransport());
        assertEquals(original.getCommand(), reloaded.getCommand());
        assertEquals(original.getArgs(), reloaded.getArgs());
        assertEquals(original.getEnv(), reloaded.getEnv());
        assertEquals(original.isEnabled(), reloaded.isEnabled());
        assertEquals(original.isAutoConnect(), reloaded.isAutoConnect());
        assertEquals(original.getAllowedTools(), reloaded.getAllowedTools());
        assertEquals(original.isExposeResourcesAsTools(), reloaded.isExposeResourcesAsTools());
    }

    @Test
    void jsonPredatingArgsAndEnvDefaultsToEmptyNotNull() {
        // Simulates a config file saved by a build before args/env existed: the keys are simply
        // absent, which must NOT surface as null (getArgs()/getEnv() would then NPE the moment
        // McpClientManager iterates them).
        String legacyJson = "{"
                + "\"id\":\"abc-123\","
                + "\"displayName\":\"Legacy Server\","
                + "\"transport\":\"stdio\","
                + "\"command\":\"python3 server.py --flag\","
                + "\"enabled\":true,"
                + "\"autoConnect\":true,"
                + "\"allowedTools\":[],"
                + "\"exposeResourcesAsTools\":false"
                + "}";

        AiMcpServerConfig loaded = GSON.fromJson(legacyJson, AiMcpServerConfig.class);

        assertEquals(List.of(), loaded.getArgs());
        assertEquals(Map.of(), loaded.getEnv());
        assertEquals("python3 server.py --flag", loaded.getCommand());
    }

    @Test
    void explicitJsonNullArgsAndEnvAlsoDefaultToEmpty() {
        // Gson populates fields via reflection when a key IS present, bypassing setArgs/setEnv
        // entirely — an explicit "args": null would otherwise leave the field genuinely null.
        String json = "{\"id\":\"x\",\"displayName\":\"S\",\"transport\":\"stdio\","
                + "\"args\":null,\"env\":null,\"enabled\":false,\"autoConnect\":true,"
                + "\"allowedTools\":[],\"exposeResourcesAsTools\":false}";

        AiMcpServerConfig loaded = GSON.fromJson(json, AiMcpServerConfig.class);

        assertEquals(List.of(), loaded.getArgs());
        assertEquals(Map.of(), loaded.getEnv());
    }

    @Test
    void freshConfigDefaultsToEmptyArgsAndEnv() {
        AiMcpServerConfig config = new AiMcpServerConfig();

        assertTrue(config.getArgs().isEmpty());
        assertTrue(config.getEnv().isEmpty());
    }

    @Test
    void settersRejectNullAndDefensivelyCopy() {
        AiMcpServerConfig config = new AiMcpServerConfig();

        config.setArgs(null);
        config.setEnv(null);
        assertTrue(config.getArgs().isEmpty());
        assertTrue(config.getEnv().isEmpty());

        List<String> mutableArgs = new java.util.ArrayList<>(List.of("--a"));
        Map<String, String> mutableEnv = new LinkedHashMap<>(Map.of("K", "V"));
        config.setArgs(mutableArgs);
        config.setEnv(mutableEnv);
        mutableArgs.add("--b"); // mutating the caller's list afterward must not leak into config
        mutableEnv.put("K2", "V2");

        assertEquals(List.of("--a"), config.getArgs());
        assertEquals(Map.of("K", "V"), config.getEnv());
    }
}
