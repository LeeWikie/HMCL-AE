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
package org.jackhuang.hmcl.ui.ai;

import org.jackhuang.hmcl.ai.mcp.AiMcpServerConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Round-trip and validation coverage for {@link McpServerJsonCodec}, the framework-free logic
/// behind the MCP server JSON editor dialog: {@code toJson(server)} seeds the editor,
/// {@code validate(text)} gates the accept button, {@code apply(server, text)} writes the edited
/// text back onto the config. Deliberately kept out of JavaFX/{@code AISettingsPage} so it can be
/// tested with a plain JUnit test.
class McpServerJsonCodecTest {

    @Test
    void editAndSaveRoundTripsIncludingArgsAndEnv() {
        AiMcpServerConfig server = new AiMcpServerConfig();
        server.setDisplayName("Original");
        server.setCommand("npx");
        server.setTransport("stdio");

        // "编辑 JSON → 保存 → 重新加载" — simulate the user opening the editor, typing a change,
        // and hitting save.
        String seeded = McpServerJsonCodec.toJson(server);
        String edited = seeded
                .replace("\"Original\"", "\"Renamed\"")
                .replace("\"args\": []", "\"args\": [\"-y\", \"@my/mcp-server\"]")
                .replace("\"env\": {}", "\"env\": {\"API_KEY\": \"secret\"}");

        assertNull(McpServerJsonCodec.validate(edited), "hand-edited JSON with valid new args/env "
                + "must validate cleanly");

        McpServerJsonCodec.apply(server, edited);

        assertEquals("Renamed", server.getDisplayName());
        assertEquals(List.of("-y", "@my/mcp-server"), server.getArgs());
        assertEquals(Map.of("API_KEY", "secret"), server.getEnv());

        // Reload: re-serialize the now-saved server and confirm it reflects the same edit — this is
        // the "重新加载 → 得到同样的 AiMcpServerConfig" half of the round trip.
        String reloadedJson = McpServerJsonCodec.toJson(server);
        AiMcpServerConfig reconstructed = new AiMcpServerConfig();
        McpServerJsonCodec.apply(reconstructed, reloadedJson);
        assertEquals(server.getDisplayName(), reconstructed.getDisplayName());
        assertEquals(server.getArgs(), reconstructed.getArgs());
        assertEquals(server.getEnv(), reconstructed.getEnv());
    }

    @Test
    void toJsonExposesEveryEditableFieldButNotIdOrLastStatus() {
        AiMcpServerConfig server = new AiMcpServerConfig();
        server.setLastStatus("connected");

        String json = McpServerJsonCodec.toJson(server);

        for (String field : McpServerJsonCodec.FIELDS) {
            assertTrue(json.contains("\"" + field + "\""), "seeded JSON should contain field " + field);
        }
        assertTrue(!json.contains("\"id\""), "id must not be user-editable (identity bookkeeping)");
        assertTrue(!json.contains("\"lastStatus\""), "lastStatus must not be user-editable (app-managed)");
    }

    @Test
    void applyIgnoresIdAndLastStatusEvenIfHandTypedIntoTheJson() {
        AiMcpServerConfig server = new AiMcpServerConfig();
        String originalId = server.getId();

        String maliciousEdit = "{\"displayName\":\"S\",\"transport\":\"stdio\",\"id\":\"attacker-controlled\","
                + "\"lastStatus\":\"fake-connected\"}";
        // Unknown-ish fields id/lastStatus are rejected by validate() (not in FIELDS)...
        assertTrue(McpServerJsonCodec.validate(maliciousEdit) != null);
        // ...but even if a caller skipped validation, apply() must still never read them.
        McpServerJsonCodec.apply(server, maliciousEdit);
        assertEquals(originalId, server.getId());
        assertNull(server.getLastStatus());
    }

    @Test
    void missingFieldsAfterEditResetToDefaultsNotToThePreviousValue() {
        AiMcpServerConfig server = new AiMcpServerConfig();
        server.setAutoConnect(false); // non-default value, to prove it doesn't linger

        // User deleted everything except displayName.
        McpServerJsonCodec.apply(server, "{\"displayName\":\"Bare\"}");

        assertEquals("Bare", server.getDisplayName());
        assertEquals("stdio", server.getTransport());
        assertEquals(null, server.getCommand());
        assertTrue(server.getArgs().isEmpty());
        assertTrue(server.getEnv().isEmpty());
        assertEquals(true, server.isAutoConnect(), "a deleted autoConnect key resets to "
                + "AiMcpServerConfig's own default (true), not the previous false");
    }

    @Test
    void validateRejectsUnknownField() {
        String error = McpServerJsonCodec.validate("{\"displayName\":\"S\",\"typo_field\":true}");
        assertTrue(error != null && error.contains("typo_field"));
    }

    @Test
    void validateRejectsWrongTypeForArgs() {
        String error = McpServerJsonCodec.validate("{\"args\": \"not-an-array\"}");
        assertTrue(error != null && error.contains("args"));
    }

    @Test
    void validateRejectsNonStringArrayElement() {
        String error = McpServerJsonCodec.validate("{\"args\": [\"ok\", 42]}");
        assertTrue(error != null && error.contains("args"));
    }

    @Test
    void validateRejectsNonStringEnvValue() {
        String error = McpServerJsonCodec.validate("{\"env\": {\"KEY\": 123}}");
        assertTrue(error != null && error.contains("env"));
    }

    @Test
    void validateRejectsInvalidTransport() {
        String error = McpServerJsonCodec.validate("{\"transport\": \"websocket\"}");
        assertTrue(error != null && error.contains("transport"));
    }

    @Test
    void validateRejectsMalformedJson() {
        String error = McpServerJsonCodec.validate("{ not json");
        assertTrue(error != null);
    }

    @Test
    void validateRejectsNonObjectTopLevel() {
        String error = McpServerJsonCodec.validate("[1, 2, 3]");
        assertTrue(error != null);
    }

    @Test
    void validateAcceptsFullyPopulatedConfig() {
        AiMcpServerConfig server = new AiMcpServerConfig();
        server.setDisplayName("Full");
        server.setTransport("http");
        server.setUrl("https://example.com/mcp");
        server.setArgs(List.of("a", "b"));
        Map<String, String> env = new LinkedHashMap<>();
        env.put("K", "V");
        server.setEnv(env);
        server.setEnabled(true);
        server.setAutoConnect(false);
        server.setAllowedTools(List.of("tool1"));
        server.setExposeResourcesAsTools(true);

        assertNull(McpServerJsonCodec.validate(McpServerJsonCodec.toJson(server)));
    }
}
