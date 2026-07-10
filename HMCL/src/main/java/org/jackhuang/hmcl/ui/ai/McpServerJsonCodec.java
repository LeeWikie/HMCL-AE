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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.ai.mcp.AiMcpServerConfig;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Converts an {@link AiMcpServerConfig} to and from the free-form JSON text edited in
/// {@code AISettingsPage#editMcpServer}'s {@link org.jackhuang.hmcl.ui.construct.JsonEditorDialogPane}.
///
/// Kept framework-free (no JavaFX) on purpose so it can be unit tested directly, unlike the
/// JavaFX-toolkit-dependent {@code AISettingsPage} it serves.
final class McpServerJsonCodec {

    private McpServerJsonCodec() {
    }

    // serializeNulls(): without it, Gson silently DROPS a map entry whose value is null — a fresh
    // stdio server (command set, url still null) would seed an editor missing the "url" key
    // entirely, leaving the user to guess its exact spelling instead of just filling in a value.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    /// The config fields exposed for hand-editing. {@code id} and {@code lastStatus} are
    /// deliberately excluded: they are internal bookkeeping (identity used to tell an edited server
    /// apart from a brand new one, and a status the app itself manages) — letting a hand edit
    /// clobber either would be a footgun, not a feature.
    static final List<String> FIELDS = List.of(
            "displayName", "transport", "command", "args", "env", "url",
            "enabled", "autoConnect", "allowedTools", "exposeResourcesAsTools");

    /// Renders {@code server}'s editable fields as pretty-printed JSON text, to seed the editor.
    static String toJson(AiMcpServerConfig server) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("displayName", server.getDisplayName());
        map.put("transport", server.getTransport());
        map.put("command", server.getCommand());
        map.put("args", server.getArgs());
        map.put("env", server.getEnv());
        map.put("url", server.getUrl());
        map.put("enabled", server.isEnabled());
        map.put("autoConnect", server.isAutoConnect());
        map.put("allowedTools", server.getAllowedTools());
        map.put("exposeResourcesAsTools", server.isExposeResourcesAsTools());
        return GSON.toJson(map);
    }

    /// Parses and structurally validates the editor's JSON text.
    ///
    /// @return {@code null} if {@code text} is acceptable, otherwise a user-facing (Chinese) error
    /// message describing the first problem found.
    @Nullable
    static String validate(String text) {
        JsonObject obj;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                return i18n("ai.settings.mcp.validate.top_level");
            }
            obj = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            return i18n("ai.settings.mcp.validate.syntax", e.getMessage());
        }

        for (String key : obj.keySet()) {
            if (!FIELDS.contains(key)) {
                return i18n("ai.settings.mcp.validate.unknown_field", key, String.join(", ", FIELDS));
            }
        }

        if (present(obj, "displayName") && !isJsonString(obj.get("displayName"))) {
            return i18n("ai.settings.mcp.validate.string_field", "displayName");
        }
        if (present(obj, "transport")) {
            JsonElement t = obj.get("transport");
            if (!isJsonString(t) || !(t.getAsString().equals("stdio") || t.getAsString().equals("http"))) {
                return i18n("ai.settings.mcp.validate.transport");
            }
        }
        if (present(obj, "command") && !isJsonString(obj.get("command"))) {
            return i18n("ai.settings.mcp.validate.string_or_null", "command");
        }
        if (present(obj, "url") && !isJsonString(obj.get("url"))) {
            return i18n("ai.settings.mcp.validate.string_or_null", "url");
        }
        String argsError = validateStringArray(obj, "args");
        if (argsError != null) return argsError;
        String allowedToolsError = validateStringArray(obj, "allowedTools");
        if (allowedToolsError != null) return allowedToolsError;
        if (present(obj, "env")) {
            JsonElement envEl = obj.get("env");
            if (!envEl.isJsonObject()) return i18n("ai.settings.mcp.validate.env_object");
            for (Map.Entry<String, JsonElement> e : envEl.getAsJsonObject().entrySet()) {
                if (!isJsonString(e.getValue())) return i18n("ai.settings.mcp.validate.env_value", e.getKey());
            }
        }
        for (String boolField : new String[]{"enabled", "autoConnect", "exposeResourcesAsTools"}) {
            if (present(obj, boolField) && !isJsonBoolean(obj.get(boolField))) {
                return i18n("ai.settings.mcp.validate.bool_field", boolField);
            }
        }
        return null;
    }

    /// Applies already-{@link #validate validated} JSON text onto {@code server}. A field missing
    /// from the JSON (the user deleted it) resets to the same default a fresh
    /// {@code new AiMcpServerConfig()} would use — not whatever {@code server} previously held —
    /// since the editor always seeds the text with every current field, so a missing field can only
    /// mean the user intentionally removed it.
    ///
    /// @throws JsonParseException if {@code text} is not valid, well-formed JSON — callers must
    /// call {@link #validate} first and only call this once that returned {@code null}.
    static void apply(AiMcpServerConfig server, String text) {
        JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
        server.setDisplayName(present(obj, "displayName") ? obj.get("displayName").getAsString() : "MCP Server");
        server.setTransport(present(obj, "transport") ? obj.get("transport").getAsString() : "stdio");
        server.setCommand(present(obj, "command") ? obj.get("command").getAsString() : null);
        server.setUrl(present(obj, "url") ? obj.get("url").getAsString() : null);
        server.setArgs(readStringArray(obj, "args"));
        server.setAllowedTools(readStringArray(obj, "allowedTools"));
        Map<String, String> env = new LinkedHashMap<>();
        if (present(obj, "env")) {
            for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("env").entrySet()) {
                env.put(e.getKey(), e.getValue().getAsString());
            }
        }
        server.setEnv(env);
        server.setEnabled(present(obj, "enabled") && obj.get("enabled").getAsBoolean());
        server.setAutoConnect(!present(obj, "autoConnect") || obj.get("autoConnect").getAsBoolean());
        server.setExposeResourcesAsTools(present(obj, "exposeResourcesAsTools") && obj.get("exposeResourcesAsTools").getAsBoolean());
    }

    @Nullable
    private static String validateStringArray(JsonObject obj, String field) {
        if (!present(obj, field)) return null;
        JsonElement el = obj.get(field);
        if (!el.isJsonArray()) return i18n("ai.settings.mcp.validate.string_array", field);
        for (JsonElement e : el.getAsJsonArray()) {
            if (!isJsonString(e)) return i18n("ai.settings.mcp.validate.string_array_item", field);
        }
        return null;
    }

    private static List<String> readStringArray(JsonObject obj, String field) {
        List<String> list = new ArrayList<>();
        if (present(obj, field)) {
            for (JsonElement e : obj.getAsJsonArray(field)) {
                list.add(e.getAsString());
            }
        }
        return list;
    }

    /// @return whether {@code field} is present in {@code obj} with a non-null value (JSON
    /// {@code null} is treated the same as absent — both fall back to the field's default).
    private static boolean present(JsonObject obj, String field) {
        return obj.has(field) && !obj.get(field).isJsonNull();
    }

    private static boolean isJsonString(JsonElement el) {
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isString();
    }

    private static boolean isJsonBoolean(JsonElement el) {
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean();
    }
}
