package org.jackhuang.hmcl.ai.langchain4j;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.jackhuang.hmcl.ai.trace.TraceContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Locks what a trace event captures: the COMPLETE request (full conversation incl. system prompt +
/// tool schemas), the raw response (text + tool calls + finishReason), complete tool IO with a
/// truncated-for-model flag, and guard decisions. This is the "记什么" contract.
public class TraceEventsTest {

    private static final TraceContext CTX = new TraceContext("sess", "turn-1");

    @Test
    public void requestCapturesFullConversationAndTools() {
        List<ChatMessage> conv = List.of(
                SystemMessage.from("EPISTEMIC STANCE: training memory is stale."),
                UserMessage.from("给我做多选题"));
        ToolSpecification ask = ToolSpecification.builder()
                .name("ask").description("ask the user structured questions").build();

        JsonObject e = TraceEvents.request(CTX, 0, 0, conv, List.of(ask), true);

        assertEquals("request", e.get("type").getAsString());
        assertEquals("turn-1", e.get("turnId").getAsString());
        assertTrue(e.get("toolsOn").getAsBoolean());

        JsonArray messages = e.getAsJsonArray("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertTrue(messages.get(0).getAsJsonObject().get("content").getAsString().contains("EPISTEMIC"),
                "full system prompt captured, not summarised");
        assertEquals("给我做多选题", messages.get(1).getAsJsonObject().get("content").getAsString());

        JsonArray tools = e.getAsJsonArray("tools");
        assertEquals(1, tools.size());
        assertEquals("ask", tools.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    public void responseCapturesRawTextToolCallsAndFinishReason() {
        AiMessage ai = AiMessage.builder()
                .text("let me search")
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .name("search_mods").arguments("{\"query\":\"create\"}").build()))
                .build();

        JsonObject e = TraceEvents.response(CTX, 1, ai, "LENGTH", 1200, 40, 1240, 850L);

        assertEquals("response", e.get("type").getAsString());
        assertEquals("LENGTH", e.get("finishReason").getAsString(), "finishReason surfaced (length truth)");
        assertEquals(1200, e.get("inTokens").getAsInt());
        assertEquals(850L, e.get("elapsedMs").getAsLong());
        assertEquals("let me search", e.get("content").getAsString());
        JsonArray calls = e.getAsJsonArray("toolCalls");
        assertEquals("search_mods", calls.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("{\"query\":\"create\"}", calls.get(0).getAsJsonObject().get("arguments").getAsString());
    }

    @Test
    public void toolCapturesCompleteResultAndTruncationFlag() {
        JsonObject e = TraceEvents.tool(CTX, 2, "read", "{\"path\":\"log\"}",
                "the full untruncated 20000-char result...", true, true);

        assertEquals("tool", e.get("type").getAsString());
        assertEquals("read", e.get("name").getAsString());
        assertEquals("{\"path\":\"log\"}", e.get("arguments").getAsString());
        assertTrue(e.get("result").getAsString().contains("full untruncated"));
        assertTrue(e.get("success").getAsBoolean());
        assertTrue(e.get("truncatedForModel").getAsBoolean(), "records that the model saw a truncated view");
    }

    @Test
    public void guardCapturesKindAndDetail() {
        JsonObject e = TraceEvents.guard(CTX, 3, "DUP_BLOCKED", "search_mods x3");
        assertEquals("guard", e.get("type").getAsString());
        assertEquals("DUP_BLOCKED", e.get("kind").getAsString());
        assertEquals("search_mods x3", e.get("detail").getAsString());
        assertEquals(3, e.get("cycle").getAsInt());
    }

    @Test
    public void toolResultMessageInConversationIsCaptured() {
        List<ChatMessage> conv = List.of(
                ToolExecutionResultMessage.from("id1", "list_mods", "no mods folder yet"));
        JsonObject e = TraceEvents.request(CTX, 1, 0, conv, null, false);
        JsonObject m = e.getAsJsonArray("messages").get(0).getAsJsonObject();
        assertEquals("tool", m.get("role").getAsString());
        assertEquals("list_mods", m.get("toolName").getAsString());
        assertEquals("no mods folder yet", m.get("content").getAsString());
    }
}
