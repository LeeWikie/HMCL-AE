package org.jackhuang.hmcl.ai.langchain4j;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.jackhuang.hmcl.ai.AiApprovalMode;
import org.jackhuang.hmcl.ai.llm.LlmException;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.ai.llm.LlmStreamCallback;
import org.jackhuang.hmcl.ai.llm.LlmUsage;
import org.jackhuang.hmcl.ai.tools.AiExecutionPolicy;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.trace.TraceRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/// The mock-LLM end-to-end test the project never had: drive the REAL streaming tool loop with a
/// scripted fake model (turn 1 asks for a tool, turn 2 answers) and assert the trace on disk holds
/// the complete truth — full request messages, raw response with finishReason, and the COMPLETE
/// tool result (not the 300-char UI summary / truncated model view). This is the seed of the
/// fake-LLM regression harness the rewrite will build on.
public class TraceLoopEndToEndTest {

    @AfterEach
    public void reset() {
        TraceRecorder.configure(null, false);
    }

    /// A stub tool whose result is long enough to prove the trace keeps the FULL text.
    private static final class StubSearchTool implements Tool {
        static final String LONG_RESULT = "Found 12 Create addons for 'create': "
                + "create-steam-n-rails, createaddition, ... " + "x".repeat(500);
        @Override public String getName() { return "search_mods"; }
        @Override public String getDescription() { return "search mods (test stub)"; }
        @Override public ToolResult execute(Map<String, Object> parameters) { return ToolResult.success(LONG_RESULT); }
    }

    private static ChatResponse toolCall(String tool, String args) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.builder()
                        .toolExecutionRequests(List.of(
                                ToolExecutionRequest.builder().id("c1").name(tool).arguments(args).build()))
                        .build())
                .finishReason(FinishReason.TOOL_EXECUTION)
                .tokenUsage(new TokenUsage(100, 10))
                .build();
    }

    private static ChatResponse text(String t, FinishReason reason) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(t))
                .finishReason(reason)
                .tokenUsage(new TokenUsage(120, 20))
                .build();
    }

    /// Streaming model that replays scripted responses, one per chat() call.
    private static final class FakeStreamingModel implements StreamingChatModel {
        private final Queue<ChatResponse> scripted;
        FakeStreamingModel(ChatResponse... rs) { scripted = new ArrayDeque<>(List.of(rs)); }
        @Override public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            ChatResponse r = scripted.poll();
            if (r == null) { handler.onError(new RuntimeException("no scripted response")); return; }
            handler.onCompleteResponse(r);
        }
    }

    private static final class FakeChatModel implements ChatModel {
        @Override public ChatResponse chat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from("")).build();
        }
    }

    @Test
    public void tracesFullLoopToDisk(@TempDir Path traceDir) throws Exception {
        TraceRecorder.configure(traceDir, true);

        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubSearchTool());
        // YOLO + null handlers: the read-ish stub runs without confirmation.
        LangChain4jToolAdapter toolAdapter = new LangChain4jToolAdapter(
                registry, new AiExecutionPolicy(AiApprovalMode.YOLO, false), null, null);

        StreamingChatModel streaming = new FakeStreamingModel(
                toolCall("search_mods", "{\"query\":\"create\"}"),   // cycle 0: ask for the tool
                text("这是给你的清单。", FinishReason.STOP));           // cycle 1: final answer

        LangChain4jChatAdapter adapter = new LangChain4jChatAdapter(new FakeChatModel(), streaming, toolAdapter);

        CountDownLatch done = new CountDownLatch(1);
        adapter.beginTurn("sess-e2e", "turn-e2e");
        adapter.sendMessageStreaming(
                List.of(new LlmMessage("system", "EPISTEMIC STANCE ..."),
                        new LlmMessage("user", "我需要一些上好的Create附属")),
                new LlmStreamCallback() {
                    @Override public void onToken(String t) { }
                    @Override public void onUsage(LlmUsage u) { }
                    @Override public void onToolActivity(String name, String args) { }
                    @Override public void onToolResult(String name, boolean success, String summary) { }
                    @Override public void onComplete(String content) { done.countDown(); }
                    @Override public void onError(LlmException e) { done.countDown(); }
                });
        assertTrue(done.await(10, TimeUnit.SECONDS), "turn completed");

        Path f = traceDir.resolve("sess-e2e.jsonl");
        assertTrue(Files.exists(f), "trace file written");
        List<String> lines = Files.readAllLines(f);

        boolean sawRequestWithSystem = false, sawToolCallResponse = false, sawFullToolResult = false, sawFinalStop = false;
        for (String line : lines) {
            JsonObject e = JsonParser.parseString(line).getAsJsonObject();
            assertEquals("turn-e2e", e.get("turnId").getAsString(), "every event tagged with the turn");
            switch (e.get("type").getAsString()) {
                case "request" -> {
                    String first = e.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
                    if (first.contains("EPISTEMIC STANCE")) sawRequestWithSystem = true;
                }
                case "response" -> {
                    if (e.has("toolCalls")) sawToolCallResponse = true;
                    if ("STOP".equals(e.has("finishReason") ? e.get("finishReason").getAsString() : "")) sawFinalStop = true;
                }
                case "tool" -> {
                    // the COMPLETE 500+ char result, not the 300-char UI summary
                    if (e.get("result").getAsString().contains(StubSearchTool.LONG_RESULT)) sawFullToolResult = true;
                }
                default -> { }
            }
        }
        assertTrue(sawRequestWithSystem, "request event captured the full system prompt");
        assertTrue(sawToolCallResponse, "response event captured the raw tool call");
        assertTrue(sawFullToolResult, "tool event captured the COMPLETE result (not a summary)");
        assertTrue(sawFinalStop, "final response captured finishReason=STOP");
    }
}
