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
package org.jackhuang.hmcl.ai.diagnostic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Locks the simplified "what the chat UI actually shows" trace contract: user/assistant text and
/// tool name+args+result survive, secrets embedded in tool arguments/results are redacted exactly
/// like the raw `ai-trace.jsonl` is, and nothing the chat UI does NOT render (system prompt, tool
/// schema, the wire-only `<turn-context>` block) ever reaches it.
public class UiTraceBuilderTest {

    private static List<JsonObject> parseLines(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<JsonObject> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isBlank()) continue;
            out.add(JsonParser.parseString(line).getAsJsonObject());
        }
        return out;
    }

    @Test
    public void capturesUserAssistantAndToolCallWithArgsAndResult() {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmMessage("user", "帮我装一个光影包"));

        LlmMessage.ToolPayload payload = new LlmMessage.ToolPayload();
        payload.name = "search_mods";
        payload.argsJson = "{\"query\":\"shader\"}";
        payload.resultText = "{\"count\":3,\"top\":\"Complementary\"}";
        payload.success = true;
        messages.add(LlmMessage.toolRecord(payload, "turn-1"));

        LlmMessage assistantMsg = new LlmMessage("assistant", "已找到 3 个光影包，推荐 Complementary。");
        assistantMsg.setModel("test-model");
        messages.add(assistantMsg);

        List<JsonObject> lines = parseLines(UiTraceBuilder.build(messages));
        assertEquals(3, lines.size(), "one JSONL line per UI-visible entry");

        assertEquals("user", lines.get(0).get("type").getAsString());
        assertEquals("帮我装一个光影包", lines.get(0).get("text").getAsString());

        JsonObject tool = lines.get(1);
        assertEquals("tool", tool.get("type").getAsString());
        assertEquals("search_mods", tool.get("name").getAsString());
        assertEquals("turn-1", tool.get("turnId").getAsString());
        assertTrue(tool.get("args").getAsString().contains("shader"));
        assertTrue(tool.get("result").getAsString().contains("Complementary"));
        assertTrue(tool.get("success").getAsBoolean());

        JsonObject assistant = lines.get(2);
        assertEquals("assistant", assistant.get("type").getAsString());
        assertEquals("已找到 3 个光影包，推荐 Complementary。", assistant.get("text").getAsString());
        assertEquals("test-model", assistant.get("model").getAsString());
    }

    /// Regression fixture mirroring RedactorTest/TraceRecorderTest's fake-secret shapes: a tool's
    /// raw arguments/result carry a fake API key / access token, which must never survive into the
    /// uploadable file.
    @Test
    public void redactsSecretsInToolArgsAndResult() {
        LlmMessage.ToolPayload payload = new LlmMessage.ToolPayload();
        payload.name = "call_api";
        payload.argsJson = "{\"api_key\":\"sk-ABCDEFGHIJKLMNOP0123456789\"}";
        payload.resultText = "{\"accessToken\":\"eyJhbGciOISECRETVALUE0123456789\"}";
        payload.success = true;
        List<LlmMessage> messages = List.of(LlmMessage.toolRecord(payload, null));

        String text = new String(UiTraceBuilder.build(messages), StandardCharsets.UTF_8);

        assertFalse(text.contains("sk-ABCDEFGHIJKLMNOP"), "fake secret in tool args must be redacted: " + text);
        assertFalse(text.contains("eyJhbGciOISECRETVALUE"), "fake secret in tool result must be redacted: " + text);
        assertTrue(text.contains("[REDACTED]"));
        assertTrue(text.contains("call_api"), "non-secret tool name must survive");
    }

    @Test
    public void neverLeaksSystemPromptText() {
        // There is currently no code path that puts a "system" role message into a persisted
        // session (buildMessages() only ever adds it to the OUTBOUND wire copy, never to
        // session.getMessages()) — this locks that in defensively so a future regression can't
        // smuggle the system prompt into the uploadable file via the session model.
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmMessage("system", "You are HMCL's assistant. Tools: [huge schema...]"));
        messages.add(new LlmMessage("user", "hi"));

        String text = new String(UiTraceBuilder.build(messages), StandardCharsets.UTF_8);
        assertFalse(text.contains("HMCL's assistant"), "system prompt text must never leak into the UI trace");
        assertFalse(text.contains("huge schema"));
        assertTrue(text.contains("hi"));
    }

    @Test
    public void neverLeaksTheWireOnlyTurnContextBlockAppendedForTheModel() {
        // Mirrors ChatAgent#attachVolatileSuffix: the <turn-context> block is appended to a NEW,
        // wire-only LlmMessage built fresh for the outbound request; the session's OWN message
        // (what this builder reads) never carries it. Simulate the original, unenriched message.
        List<LlmMessage> messages = List.of(new LlmMessage("user", "继续"));
        String text = new String(UiTraceBuilder.build(messages), StandardCharsets.UTF_8);
        assertFalse(text.contains("<turn-context>"));
    }

    @Test
    public void emptyMessageListProducesEmptyOutput() {
        assertEquals(0, UiTraceBuilder.build(List.<LlmMessage>of()).length);
    }

    @Test
    public void toolPayloadWithoutArgsOrResultOmitsThoseFields() {
        LlmMessage.ToolPayload payload = new LlmMessage.ToolPayload();
        payload.name = "no_args_tool";
        payload.success = false;
        List<JsonObject> lines = parseLines(UiTraceBuilder.build(List.of(LlmMessage.toolRecord(payload, null))));
        assertEquals(1, lines.size());
        assertFalse(lines.get(0).has("args"));
        assertFalse(lines.get(0).has("result"));
        assertFalse(lines.get(0).get("success").getAsBoolean());
    }
}
