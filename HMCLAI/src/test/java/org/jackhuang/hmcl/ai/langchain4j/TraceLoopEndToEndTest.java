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
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
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

    /// Echoes back its `query` argument after a fixed delay — used to prove a batch of independent
    /// READ_ONLY calls runs CONCURRENTLY (total wall time well under N × delay) and that each
    /// result still ends up correctly matched to the call that produced it (not mixed up by
    /// out-of-order completion).
    private static final class DelayedEchoTool implements ToolSpec {
        static final long DELAY_MS = 150;
        @Override public String getName() { return "search"; }
        @Override public String getDescription() { return "delayed echo (test stub)"; }
        @Override public ToolPermission getPermission() { return ToolPermission.READ_ONLY; }
        @Override public ToolResult execute(Map<String, Object> parameters) {
            try {
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.success("result-for-" + parameters.get("query"));
        }
    }

    /// A READ_ONLY test-double tool named exactly {@code "ask"} (mirroring the real
    /// {@code AskTool}'s declared permission) — used to prove the concurrent-ask race fix: a batch
    /// containing a call named "ask" alongside another independent READ_ONLY call must NOT be
    /// dispatched via the parallel path, even though every call in the batch is READ_ONLY. Real
    /// {@code AskTool} is UI-backed (AIMainPage's single {@code activeAsk}/{@code askPanel}), which
    /// this stub deliberately does not reimplement — only the name + permission + delay shape
    /// matter for exercising {@code LangChain4jChatAdapter}'s dispatch decision.
    private static final class DelayedAskTool implements ToolSpec {
        static final long DELAY_MS = 150;
        @Override public String getName() { return "ask"; }
        @Override public String getDescription() { return "delayed echo, named ask (test stub)"; }
        @Override public ToolPermission getPermission() { return ToolPermission.READ_ONLY; }
        @Override public ToolResult execute(Map<String, Object> parameters) {
            try {
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.success("result-for-" + parameters.get("query"));
        }
    }

    /// Mirrors how every REAL merged domain tool (SearchTool, InstanceTool, GameTool, NbtTool,
    /// AccountTool, JobTool) actually declares its permission: overrides ONLY the action-aware
    /// {@link ToolSpec#getPermission(Map)} — never the no-arg {@link ToolSpec#getPermission()},
    /// which is left at {@link ToolSpec}'s {@code CONTROLLED_WRITE} default. Regression coverage for
    /// resolvePermission (see {@link LangChain4jToolAdapter#resolvePermission}) must use a stub
    /// shaped exactly like this — unlike {@link DelayedEchoTool}, which overrides the no-arg
    /// overload directly and so never exercised the bug where resolving by tool name alone (instead
    /// of the actual request/arguments) fell through to the conservative no-arg default.
    private static final class DelayedEchoActionAwareTool implements ToolSpec {
        static final long DELAY_MS = 150;
        @Override public String getName() { return "search"; }
        @Override public String getDescription() { return "delayed echo, action-aware permission (test stub)"; }
        @Override public ToolPermission getPermission(Map<String, Object> parameters) { return ToolPermission.READ_ONLY; }
        @Override public ToolResult execute(Map<String, Object> parameters) {
            try {
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolResult.success("result-for-" + parameters.get("query"));
        }
    }

    /// Same as {@link StubSearchTool} but explicitly READ_ONLY (matching the real search tool's
    /// declared permission) — needed so the verify-on-stop guard (which force-injects an extra
    /// cycle after any non-READ_ONLY tool call) doesn't demand a 3rd scripted response in tests
    /// that intentionally script only 2 cycles.
    private static final class ReadOnlySearchTool implements ToolSpec {
        @Override public String getName() { return "search_mods"; }
        @Override public String getDescription() { return "search mods (read-only test stub)"; }
        @Override public ToolPermission getPermission() { return ToolPermission.READ_ONLY; }
        @Override public ToolResult execute(Map<String, Object> parameters) {
            return ToolResult.success(StubSearchTool.LONG_RESULT);
        }
    }

    /// Domain-tool-shaped stub for the verify-on-stop ledger tests below: a single tool NAME
    /// ("instance_stub") that, like the real merged `instance` tool, dispatches many different
    /// actions on many different targets. `action` in {@code READ_ACTIONS} is READ_ONLY (mirrors
    /// e.g. `details`/`list`); every other action is CONTROLLED_WRITE (mirrors e.g.
    /// `set_memory`/`set_option`) and so counts as a verifiable risky write (see
    /// {@code LangChain4jChatAdapter#isVerifiableRiskyWrite} — the name isn't "write"/"edit").
    /// Declares permission ONLY via the action-aware overload, exactly like every real merged
    /// domain tool (see {@link DelayedEchoActionAwareTool}'s doc).
    private static final class InstanceStubTool implements ToolSpec {
        static final java.util.Set<String> READ_ACTIONS = java.util.Set.of("details", "list");
        @Override public String getName() { return "instance_stub"; }
        @Override public String getDescription() { return "instance-shaped domain tool stub (test stub)"; }
        @Override public ToolPermission getPermission(Map<String, Object> parameters) {
            Object action = parameters.get("action");
            return READ_ACTIONS.contains(action) ? ToolPermission.READ_ONLY : ToolPermission.CONTROLLED_WRITE;
        }
        @Override public ToolResult execute(Map<String, Object> parameters) {
            return ToolResult.success("ok: " + parameters.get("action") + " on " + parameters.get("instance"));
        }
    }

    /// Stub for HMCL's real {@code TodoWriteTool} — same name and declared permission (READ_ONLY;
    /// maintaining the checklist doesn't mutate host state) — used by the todo-discipline guard
    /// tests below. HMCLAI cannot depend on the real tool (it lives in the JavaFX UI module), so
    /// this just needs to succeed; the guard's bookkeeping (see
    /// {@code LangChain4jChatAdapter#updateTodoLedger}) reads the raw arguments JSON directly, not
    /// this tool's return value.
    private static final class TodoWriteStubTool implements ToolSpec {
        @Override public String getName() { return "todo_write"; }
        @Override public String getDescription() { return "todo write (test stub)"; }
        @Override public ToolPermission getPermission() { return ToolPermission.READ_ONLY; }
        @Override public ToolResult execute(Map<String, Object> parameters) {
            return ToolResult.success("ok");
        }
    }

    /// Cheap READ_ONLY probe tool for the todo-staleness test below: its result echoes the query
    /// argument so every call in a long run of distinct queries gets its own unique signature (see
    /// {@code LangChain4jChatAdapter#buildLoopSignature}) — without that, repeating the SAME
    /// query/result would trip the unrelated loop-signature guard long before 15 cycles pass.
    private static final class ProbeTool implements ToolSpec {
        @Override public String getName() { return "probe"; }
        @Override public String getDescription() { return "cheap read-only probe (test stub)"; }
        @Override public ToolPermission getPermission() { return ToolPermission.READ_ONLY; }
        @Override public ToolResult execute(Map<String, Object> parameters) {
            return ToolResult.success("probed:" + parameters.get("query"));
        }
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
                registry, new AiExecutionPolicy(AiApprovalMode.AUTO, false), null, null);

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

    /// Regression test for "multi-cycle replies collapse into one bubble on reload": a 2-cycle turn
    /// (prose before a tool call, then a final answer) must fire {@code onSegmentComplete} ONCE PER
    /// CYCLE — not once for the whole turn, and not once per tool call within a cycle — so
    /// ChatAgent can persist each segment as its own message. Joining the segments in order must
    /// reproduce exactly what onComplete reports as the full text.
    @Test
    public void segmentCompleteFiresOncePerCycleInOrder() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadOnlySearchTool());
        LangChain4jToolAdapter toolAdapter = new LangChain4jToolAdapter(
                registry, new AiExecutionPolicy(AiApprovalMode.AUTO, false), null, null);

        ChatResponse proseAndToolCall = ChatResponse.builder()
                .aiMessage(AiMessage.builder()
                        .text("好的，我先搜一下。")
                        .toolExecutionRequests(List.of(
                                ToolExecutionRequest.builder().id("c1").name("search_mods")
                                        .arguments("{\"query\":\"create\"}").build()))
                        .build())
                .finishReason(FinishReason.TOOL_EXECUTION)
                .tokenUsage(new TokenUsage(100, 10))
                .build();
        StreamingChatModel streaming = new FakeStreamingModel(
                proseAndToolCall,                                    // cycle 0: prose + tool call
                text("这是给你的清单。", FinishReason.STOP));           // cycle 1: final answer

        LangChain4jChatAdapter adapter = new LangChain4jChatAdapter(new FakeChatModel(), streaming, toolAdapter);

        List<String> segments = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> finalText = new java.util.concurrent.atomic.AtomicReference<>();
        adapter.beginTurn("sess-seg", "turn-seg");
        adapter.sendMessageStreaming(
                List.of(new LlmMessage("system", "sys"),
                        new LlmMessage("user", "帮我找点Create附属")),
                new LlmStreamCallback() {
                    @Override public void onToken(String t) { }
                    @Override public void onUsage(LlmUsage u) { }
                    @Override public void onToolActivity(String name, String args) { }
                    @Override public void onToolResult(String name, boolean success, String summary) { }
                    @Override public void onSegmentComplete(String segment) { segments.add(segment); }
                    @Override public void onComplete(String content) { finalText.set(content); done.countDown(); }
                    @Override public void onError(LlmException e) { done.countDown(); }
                });
        assertTrue(done.await(10, TimeUnit.SECONDS), "turn completed");

        assertEquals(2, segments.size(), "one segment per cycle: pre-tool-call prose, then the final answer");
        assertEquals("好的，我先搜一下。", segments.get(0), "cycle 0's segment must come first, in order");
        assertEquals("这是给你的清单。", segments.get(1), "cycle 1's segment must come second");
        assertEquals(String.join("\n\n", segments), finalText.get(),
                "joining every reported segment in order must reproduce onComplete's full text");
    }

    /// Regression test for "5 independent search calls in one response ran strictly back-to-back"
    /// (a real trace's own complaint: "不知道这几个Search是不是并行的，如果不是那问题就严重了").
    /// Scripts ONE cycle with 5 tool calls to the SAME all-READ_ONLY tool (so the batch qualifies
    /// for the parallel path) and asserts: (1) wall time is well under the sequential sum, proving
    /// they actually ran concurrently, and (2) every result is still correctly matched back to the
    /// call that produced it, in the ORIGINAL request order — concurrent completion must never
    /// scramble which result belongs to which request.
    @Test
    public void independentReadOnlyCallsInOneCycleRunConcurrently() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new DelayedEchoTool());
        LangChain4jToolAdapter toolAdapter = new LangChain4jToolAdapter(
                registry, new AiExecutionPolicy(AiApprovalMode.AUTO, false), null, null);

        List<ToolExecutionRequest> calls = new java.util.ArrayList<>();
        for (String q : List.of("a", "b", "c", "d", "e")) {
            calls.add(ToolExecutionRequest.builder().id("id-" + q).name("search")
                    .arguments("{\"query\":\"" + q + "\"}").build());
        }
        ChatResponse batchCall = ChatResponse.builder()
                .aiMessage(AiMessage.builder().toolExecutionRequests(calls).build())
                .finishReason(FinishReason.TOOL_EXECUTION)
                .tokenUsage(new TokenUsage(100, 10))
                .build();
        StreamingChatModel streaming = new FakeStreamingModel(
                batchCall,
                text("都搜完了。", FinishReason.STOP));

        LangChain4jChatAdapter adapter = new LangChain4jChatAdapter(new FakeChatModel(), streaming, toolAdapter);

        List<String> resultsInOrder = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        adapter.beginTurn("sess-parallel", "turn-parallel");
        long start = System.nanoTime();
        adapter.sendMessageStreaming(
                List.of(new LlmMessage("system", "sys"), new LlmMessage("user", "查5个东西")),
                new LlmStreamCallback() {
                    @Override public void onToken(String t) { }
                    @Override public void onUsage(LlmUsage u) { }
                    @Override public void onToolActivity(String name, String args) { }
                    @Override public void onToolResult(String name, boolean success, String summary) {
                        resultsInOrder.add(summary);
                    }
                    @Override public void onComplete(String content) { done.countDown(); }
                    @Override public void onError(LlmException e) { done.countDown(); }
                });
        assertTrue(done.await(10, TimeUnit.SECONDS), "turn completed");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(5, resultsInOrder.size(), "all 5 calls must produce a result");
        assertEquals(
                List.of("result-for-a", "result-for-b", "result-for-c", "result-for-d", "result-for-e"),
                resultsInOrder,
                "results must be delivered in ORIGINAL request order, matched to their own call, "
                        + "even though they may complete out of order when run concurrently");
        long sequentialWorstCase = 5 * DelayedEchoTool.DELAY_MS;
        assertTrue(elapsedMs < sequentialWorstCase,
                "5 independent READ_ONLY calls (each " + DelayedEchoTool.DELAY_MS + "ms) took " + elapsedMs
                        + "ms — expected well under the " + sequentialWorstCase
                        + "ms sequential sum, proving they ran concurrently, not back-to-back");
    }

    /// Regression test for the concurrent-`ask` race: because AskTool is declared READ_ONLY, a
    /// batch of 2+ calls that includes an `ask` call is otherwise eligible for the parallel
    /// dispatch path just like {@link #independentReadOnlyCallsInOneCycleRunConcurrently}'s all-
    /// `search` batch. But `ask` is backed by AIMainPage's single `activeAsk` future / one
    /// `askPanel` widget — a second concurrent `ask` call's showAskPanel() cancels whatever panel
    /// is already pending (completing the FIRST ask's future exceptionally as "the user
    /// cancelled"), so only the last `ask` call in the batch would ever actually reach the user.
    /// Scripts ONE cycle with an "ask" call alongside an independent "search" call (both READ_ONLY)
    /// and asserts wall time is close to the FULL sequential sum (not well under it, as the
    /// all-READ_ONLY concurrency tests above assert) — proving the batch fell back to the
    /// sequential path specifically because it contains "ask", not because parallel dispatch is
    /// broken in general.
    @Test
    public void batchContainingAskToolNeverDispatchesViaParallelPath() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new DelayedAskTool());
        registry.register(new DelayedEchoTool());
        LangChain4jToolAdapter toolAdapter = new LangChain4jToolAdapter(
                registry, new AiExecutionPolicy(AiApprovalMode.AUTO, false), null, null);

        List<ToolExecutionRequest> calls = List.of(
                ToolExecutionRequest.builder().id("id-ask").name("ask").arguments("{\"query\":\"ask-1\"}").build(),
                ToolExecutionRequest.builder().id("id-search").name("search").arguments("{\"query\":\"s-1\"}").build());
        ChatResponse batchCall = ChatResponse.builder()
                .aiMessage(AiMessage.builder().toolExecutionRequests(calls).build())
                .finishReason(FinishReason.TOOL_EXECUTION)
                .tokenUsage(new TokenUsage(100, 10))
                .build();
        StreamingChatModel streaming = new FakeStreamingModel(
                batchCall,
                text("都问完了。", FinishReason.STOP));

        LangChain4jChatAdapter adapter = new LangChain4jChatAdapter(new FakeChatModel(), streaming, toolAdapter);

        List<String> resultsInOrder = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        adapter.beginTurn("sess-ask-race", "turn-ask-race");
        long start = System.nanoTime();
        adapter.sendMessageStreaming(
                List.of(new LlmMessage("system", "sys"), new LlmMessage("user", "问两个问题")),
                new LlmStreamCallback() {
                    @Override public void onToken(String t) { }
                    @Override public void onUsage(LlmUsage u) { }
                    @Override public void onToolActivity(String name, String args) { }
                    @Override public void onToolResult(String name, boolean success, String summary) {
                        resultsInOrder.add(summary);
                    }
                    @Override public void onComplete(String content) { done.countDown(); }
                    @Override public void onError(LlmException e) { done.countDown(); }
                });
        assertTrue(done.await(10, TimeUnit.SECONDS), "turn completed");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(2, resultsInOrder.size(), "both calls must produce a result");
        assertEquals(List.of("result-for-ask-1", "result-for-s-1"), resultsInOrder,
                "results still delivered in original request order via the sequential path");
        long sequentialFloor = DelayedAskTool.DELAY_MS + DelayedEchoTool.DELAY_MS;
        assertTrue(elapsedMs >= sequentialFloor,
                "batch containing 'ask' took only " + elapsedMs + "ms, under the " + sequentialFloor
                        + "ms sequential floor — expected the parallel path to be skipped whenever 'ask' is in "
                        + "the batch (concurrent ask panels would race, silently dropping a question)");
    }

    /// Regression test for resolvePermission() resolving by tool name alone (falling through to
    /// ToolSpec's no-arg CONTROLLED_WRITE default) instead of the actual request's arguments.
    /// Identical in shape to {@link #independentReadOnlyCallsInOneCycleRunConcurrently}, but uses
    /// {@link DelayedEchoActionAwareTool} — declared exactly like every real merged domain tool
    /// (SearchTool/InstanceTool/GameTool/NbtTool/AccountTool/JobTool): READ_ONLY only via the
    /// parameterized {@code getPermission(Map)} overload, no-arg {@code getPermission()} left at its
    /// CONTROLLED_WRITE default. Before the fix, resolvePermission(String) always returned
    /// CONTROLLED_WRITE for this tool, so parallelSafe was never true and this batch of 5 calls ran
    /// strictly sequentially — the exact real-world "5 concurrent search calls" case the parallel
    /// path exists for. Asserts the same two things as the DelayedEchoTool test: concurrent wall
    /// time, and results still correctly matched back to their originating call in request order.
    @Test
    public void independentReadOnlyCallsWithActionAwarePermissionRunConcurrently() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new DelayedEchoActionAwareTool());
        LangChain4jToolAdapter toolAdapter = new LangChain4jToolAdapter(
                registry, new AiExecutionPolicy(AiApprovalMode.AUTO, false), null, null);

        List<ToolExecutionRequest> calls = new java.util.ArrayList<>();
        for (String q : List.of("a", "b", "c", "d", "e")) {
            calls.add(ToolExecutionRequest.builder().id("id-" + q).name("search")
                    .arguments("{\"query\":\"" + q + "\"}").build());
        }
        ChatResponse batchCall = ChatResponse.builder()
                .aiMessage(AiMessage.builder().toolExecutionRequests(calls).build())
                .finishReason(FinishReason.TOOL_EXECUTION)
                .tokenUsage(new TokenUsage(100, 10))
                .build();
        StreamingChatModel streaming = new FakeStreamingModel(
                batchCall,
                text("都搜完了。", FinishReason.STOP));

        LangChain4jChatAdapter adapter = new LangChain4jChatAdapter(new FakeChatModel(), streaming, toolAdapter);

        List<String> resultsInOrder = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        adapter.beginTurn("sess-parallel-action-aware", "turn-parallel-action-aware");
        long start = System.nanoTime();
        adapter.sendMessageStreaming(
                List.of(new LlmMessage("system", "sys"), new LlmMessage("user", "查5个东西")),
                new LlmStreamCallback() {
                    @Override public void onToken(String t) { }
                    @Override public void onUsage(LlmUsage u) { }
                    @Override public void onToolActivity(String name, String args) { }
                    @Override public void onToolResult(String name, boolean success, String summary) {
                        resultsInOrder.add(summary);
                    }
                    @Override public void onComplete(String content) { done.countDown(); }
                    @Override public void onError(LlmException e) { done.countDown(); }
                });
        assertTrue(done.await(10, TimeUnit.SECONDS), "turn completed");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(5, resultsInOrder.size(), "all 5 calls must produce a result");
        assertEquals(
                List.of("result-for-a", "result-for-b", "result-for-c", "result-for-d", "result-for-e"),
                resultsInOrder,
                "results must be delivered in ORIGINAL request order, matched to their own call, "
                        + "even though they may complete out of order when run concurrently");
        long sequentialWorstCase = 5 * DelayedEchoActionAwareTool.DELAY_MS;
        assertTrue(elapsedMs < sequentialWorstCase,
                "5 independent action-aware-READ_ONLY calls (each " + DelayedEchoActionAwareTool.DELAY_MS
                        + "ms) took " + elapsedMs + "ms — expected well under the " + sequentialWorstCase
                        + "ms sequential sum; if this fails, resolvePermission() has regressed to "
                        + "resolving by tool name alone instead of the request's actual arguments, "
                        + "silently reverting to CONTROLLED_WRITE for every real merged domain tool");
    }

    /// Counts "guard" trace events of the given {@code kind} across a trace file's lines.
    private static long countGuardEvents(Path traceFile, String kind) throws Exception {
        long count = 0;
        for (String line : Files.readAllLines(traceFile)) {
            JsonObject e = JsonParser.parseString(line).getAsJsonObject();
            if ("guard".equals(e.get("type").getAsString())
                    && kind.equals(e.has("kind") ? e.get("kind").getAsString() : null)) {
                count++;
            }
        }
        return count;
    }

    /// End-to-end regression test for the verify-on-stop LEDGER fix (borrow-list 2.13 / failure
    /// mode E, the {@code sawRiskyWriteSinceVerify} bug): a turn that writes to TWO different
    /// targets ("instance_stub" on "A", then on "B") via the same domain-tool name, followed by only
    /// ONE read-only call that verifies target "B", must still nudge the model to verify "A" before
    /// finishing — the old single-boolean version cleared on ANY successful READ_ONLY call anywhere
    /// in the turn, so the read of "B" alone would have wrongly cleared "A"'s outstanding write too
    /// and let the turn finish immediately with NO verify-on-stop nudge at all. Scripts 5 cycles:
    /// write A, write B, read B, an attempted finish (must trigger exactly one VERIFY_ON_STOP nudge
    /// because A is still unverified), then a real final answer.
    @Test
    public void verifyOnStopNudgesForEachWriteNeedingItsOwnVerifyingRead(@TempDir Path traceDir) throws Exception {
        TraceRecorder.configure(traceDir, true);

        ToolRegistry registry = new ToolRegistry();
        registry.register(new InstanceStubTool());
        LangChain4jToolAdapter toolAdapter = new LangChain4jToolAdapter(
                registry, new AiExecutionPolicy(AiApprovalMode.AUTO, false), null, null);

        StreamingChatModel streaming = new FakeStreamingModel(
                toolCall("instance_stub", "{\"action\":\"set_memory\",\"instance\":\"A\",\"maxMemoryMB\":4096}"), // cycle 0: write A
                toolCall("instance_stub", "{\"action\":\"set_memory\",\"instance\":\"B\",\"maxMemoryMB\":2048}"), // cycle 1: write B
                toolCall("instance_stub", "{\"action\":\"details\",\"instance\":\"B\"}"),                          // cycle 2: read B only
                text("两处修改都完成了。", FinishReason.STOP),                                                          // cycle 3: tries to stop — A still unverified
                text("重新确认后，A 也已经生效。", FinishReason.STOP));                                                  // cycle 4: real final answer after the nudge

        LangChain4jChatAdapter adapter = new LangChain4jChatAdapter(new FakeChatModel(), streaming, toolAdapter);

        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> finalText = new java.util.concurrent.atomic.AtomicReference<>();
        adapter.beginTurn("sess-verify-ledger", "turn-verify-ledger");
        adapter.sendMessageStreaming(
                List.of(new LlmMessage("system", "sys"),
                        new LlmMessage("user", "把A和B两个实例的内存都改一下")),
                new LlmStreamCallback() {
                    @Override public void onToken(String t) { }
                    @Override public void onUsage(LlmUsage u) { }
                    @Override public void onToolActivity(String name, String args) { }
                    @Override public void onToolResult(String name, boolean success, String summary) { }
                    @Override public void onComplete(String content) { finalText.set(content); done.countDown(); }
                    @Override public void onError(LlmException e) { done.countDown(); }
                });
        assertTrue(done.await(10, TimeUnit.SECONDS), "turn completed");

        Path f = traceDir.resolve("sess-verify-ledger.jsonl");
        assertEquals(1, countGuardEvents(f, "VERIFY_ON_STOP"),
                "exactly one verify-on-stop nudge must fire — instance A's write was never verified, "
                        + "even though instance B's write (made via the SAME tool name) was read back "
                        + "afterward; the old single-boolean flag would have been cleared by that read "
                        + "of B and let the turn finish with NO nudge at all");
        assertEquals("两处修改都完成了。\n\n重新确认后，A 也已经生效。", finalText.get(),
                "the turn must include the forced extra verify cycle's segment appended after the "
                        + "first (pre-nudge) attempt's segment — proving a real extra cycle ran, "
                        + "not that the first attempt was accepted as-is");
    }

    /// Regression test for the common case, unchanged by the ledger fix: a SINGLE risky write
    /// immediately followed by a read-only call verifying that SAME target must NOT trigger any
    /// verify-on-stop nudge — the turn finishes on the very next model response, exactly as before.
    @Test
    public void verifyOnStopDoesNotNudgeWhenTheSingleWriteIsProperlyVerified(@TempDir Path traceDir) throws Exception {
        TraceRecorder.configure(traceDir, true);

        ToolRegistry registry = new ToolRegistry();
        registry.register(new InstanceStubTool());
        LangChain4jToolAdapter toolAdapter = new LangChain4jToolAdapter(
                registry, new AiExecutionPolicy(AiApprovalMode.AUTO, false), null, null);

        StreamingChatModel streaming = new FakeStreamingModel(
                toolCall("instance_stub", "{\"action\":\"set_memory\",\"instance\":\"A\",\"maxMemoryMB\":4096}"), // cycle 0: write A
                toolCall("instance_stub", "{\"action\":\"details\",\"instance\":\"A\"}"),                          // cycle 1: read A back
                text("修改完成，已确认生效。", FinishReason.STOP));                                                       // cycle 2: final answer

        LangChain4jChatAdapter adapter = new LangChain4jChatAdapter(new FakeChatModel(), streaming, toolAdapter);

        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> finalText = new java.util.concurrent.atomic.AtomicReference<>();
        adapter.beginTurn("sess-verify-single", "turn-verify-single");
        adapter.sendMessageStreaming(
                List.of(new LlmMessage("system", "sys"), new LlmMessage("user", "把A实例的内存改一下")),
                new LlmStreamCallback() {
                    @Override public void onToken(String t) { }
                    @Override public void onUsage(LlmUsage u) { }
                    @Override public void onToolActivity(String name, String args) { }
                    @Override public void onToolResult(String name, boolean success, String summary) { }
                    @Override public void onComplete(String content) { finalText.set(content); done.countDown(); }
                    @Override public void onError(LlmException e) { done.countDown(); }
                });
        assertTrue(done.await(10, TimeUnit.SECONDS), "turn completed");

        Path f = traceDir.resolve("sess-verify-single.jsonl");
        assertEquals(0, countGuardEvents(f, "VERIFY_ON_STOP"),
                "a write immediately followed by a read of the SAME target must not need any nudge");
        assertEquals("修改完成，已确认生效。", finalText.get(),
                "the turn must complete on the 3rd scripted response, exactly as before the ledger fix");
    }

    /// Mirrors HMCL's real {@code TodoWriteTool} schema: the arguments object nests the todo array
    /// as a JSON-ARRAY-ENCODED STRING, e.g. {@code {"todos":"[{\"content\":...}]"}}.
    private static String todoWriteArgs(String todosArrayJson) {
        return "{\"todos\":\"" + todosArrayJson.replace("\"", "\\\"") + "\"}";
    }

    /// End-to-end regression test for the todo-discipline SILENT-DISCARD guard (the real trace's own
    /// bug: a 9-item checklist abandoned mid-task for an unrelated new one, its unfinished items —
    /// some already actually done by then — never checked off). Scripts: create a 2-item list, then
    /// replace it wholesale with an unrelated single item (both original items silently vanish
    /// instead of being carried over or marked done) — must nudge exactly once.
    @Test
    public void todoDiscardNudgesWhenOutstandingItemsSilentlyVanishFromTheList(@TempDir Path traceDir) throws Exception {
        TraceRecorder.configure(traceDir, true);

        ToolRegistry registry = new ToolRegistry();
        registry.register(new TodoWriteStubTool());
        LangChain4jToolAdapter toolAdapter = new LangChain4jToolAdapter(
                registry, new AiExecutionPolicy(AiApprovalMode.AUTO, false), null, null);

        StreamingChatModel streaming = new FakeStreamingModel(
                toolCall("todo_write", todoWriteArgs(
                        "[{\"content\":\"安装 Fabric\",\"status\":\"in_progress\"},"
                        + "{\"content\":\"安装 Sodium\",\"status\":\"pending\"}]")),                 // cycle 0: create the list
                toolCall("todo_write", todoWriteArgs(
                        "[{\"content\":\"换个思路重新装\",\"status\":\"in_progress\"}]")),             // cycle 1: wholesale replace — both old items vanish
                text("弄完了。", FinishReason.STOP));                                                // cycle 2: final answer

        LangChain4jChatAdapter adapter = new LangChain4jChatAdapter(new FakeChatModel(), streaming, toolAdapter);

        CountDownLatch done = new CountDownLatch(1);
        adapter.beginTurn("sess-todo-discard", "turn-todo-discard");
        adapter.sendMessageStreaming(
                List.of(new LlmMessage("system", "sys"), new LlmMessage("user", "帮我装几个模组")),
                new LlmStreamCallback() {
                    @Override public void onToken(String t) { }
                    @Override public void onUsage(LlmUsage u) { }
                    @Override public void onToolActivity(String name, String args) { }
                    @Override public void onToolResult(String name, boolean success, String summary) { }
                    @Override public void onComplete(String content) { done.countDown(); }
                    @Override public void onError(LlmException e) { done.countDown(); }
                });
        assertTrue(done.await(10, TimeUnit.SECONDS), "turn completed");

        Path f = traceDir.resolve("sess-todo-discard.jsonl");
        assertEquals(1, countGuardEvents(f, "TODO_SILENT_DISCARD"),
                "replacing the list wholesale must nudge exactly once — '安装 Fabric' and '安装 Sodium' "
                        + "were still outstanding and vanished without being carried over or marked done");
    }

    /// Counterpart to the discard test above: a legitimate update — marking a previously-outstanding
    /// item "done" and carrying the rest over — must NOT trigger the silent-discard nudge.
    @Test
    public void todoDiscardDoesNotNudgeWhenTheListIsProperlyUpdated(@TempDir Path traceDir) throws Exception {
        TraceRecorder.configure(traceDir, true);

        ToolRegistry registry = new ToolRegistry();
        registry.register(new TodoWriteStubTool());
        LangChain4jToolAdapter toolAdapter = new LangChain4jToolAdapter(
                registry, new AiExecutionPolicy(AiApprovalMode.AUTO, false), null, null);

        StreamingChatModel streaming = new FakeStreamingModel(
                toolCall("todo_write", todoWriteArgs(
                        "[{\"content\":\"安装 Mods\",\"status\":\"in_progress\"},"
                        + "{\"content\":\"验证安装结果\",\"status\":\"pending\"}]")),                  // cycle 0: create the list
                toolCall("todo_write", todoWriteArgs(
                        "[{\"content\":\"安装 Mods\",\"status\":\"done\"},"
                        + "{\"content\":\"验证安装结果\",\"status\":\"in_progress\"}]")),              // cycle 1: legitimate check-off, nothing vanishes
                text("弄完了。", FinishReason.STOP));                                                // cycle 2: final answer

        LangChain4jChatAdapter adapter = new LangChain4jChatAdapter(new FakeChatModel(), streaming, toolAdapter);

        CountDownLatch done = new CountDownLatch(1);
        adapter.beginTurn("sess-todo-ok", "turn-todo-ok");
        adapter.sendMessageStreaming(
                List.of(new LlmMessage("system", "sys"), new LlmMessage("user", "帮我装几个模组")),
                new LlmStreamCallback() {
                    @Override public void onToken(String t) { }
                    @Override public void onUsage(LlmUsage u) { }
                    @Override public void onToolActivity(String name, String args) { }
                    @Override public void onToolResult(String name, boolean success, String summary) { }
                    @Override public void onComplete(String content) { done.countDown(); }
                    @Override public void onError(LlmException e) { done.countDown(); }
                });
        assertTrue(done.await(10, TimeUnit.SECONDS), "turn completed");

        Path f = traceDir.resolve("sess-todo-ok.jsonl");
        assertEquals(0, countGuardEvents(f, "TODO_SILENT_DISCARD"),
                "marking an item done and carrying the rest over is a legitimate update, not a discard");
    }

    /// End-to-end regression test for the todo-discipline STALENESS guard: after the list is created
    /// with an outstanding item, a long run of tool-call cycles that never touch {@code todo_write}
    /// again (while that item is still outstanding) must eventually nudge the model to go back and
    /// reconcile the checklist with reality — deliberately generous (TODO_STALE_CYCLE_LIMIT=15) so it
    /// never fires on an ordinary single-phase task.
    @Test
    public void todoStalenessNudgesAfterManyCyclesWithNoTodoWriteCall(@TempDir Path traceDir) throws Exception {
        TraceRecorder.configure(traceDir, true);

        ToolRegistry registry = new ToolRegistry();
        registry.register(new TodoWriteStubTool());
        registry.register(new ProbeTool());
        LangChain4jToolAdapter toolAdapter = new LangChain4jToolAdapter(
                registry, new AiExecutionPolicy(AiApprovalMode.AUTO, false), null, null);

        List<ChatResponse> scripted = new java.util.ArrayList<>();
        scripted.add(toolCall("todo_write", todoWriteArgs("[{\"content\":\"安装一批 Mods\",\"status\":\"in_progress\"}]")));
        // 15 cycles that never call todo_write again — each probes a DISTINCT (non-numeric) query so
        // no two calls share a loop-detection signature (digits are blanked for that check; letters
        // are not) and the unrelated loop-signature guard never fires first.
        for (char c : "abcdefghijklmno".toCharArray()) {
            scripted.add(toolCall("probe", "{\"query\":\"q" + c + "\"}"));
        }
        scripted.add(text("全部装完了。", FinishReason.STOP));

        StreamingChatModel streaming = new FakeStreamingModel(scripted.toArray(new ChatResponse[0]));
        LangChain4jChatAdapter adapter = new LangChain4jChatAdapter(new FakeChatModel(), streaming, toolAdapter);

        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> finalText = new java.util.concurrent.atomic.AtomicReference<>();
        adapter.beginTurn("sess-todo-stale", "turn-todo-stale");
        adapter.sendMessageStreaming(
                List.of(new LlmMessage("system", "sys"), new LlmMessage("user", "帮我装一批模组")),
                new LlmStreamCallback() {
                    @Override public void onToken(String t) { }
                    @Override public void onUsage(LlmUsage u) { }
                    @Override public void onToolActivity(String name, String args) { }
                    @Override public void onToolResult(String name, boolean success, String summary) { }
                    @Override public void onComplete(String content) { finalText.set(content); done.countDown(); }
                    @Override public void onError(LlmException e) { done.countDown(); }
                });
        assertTrue(done.await(10, TimeUnit.SECONDS), "turn completed");

        Path f = traceDir.resolve("sess-todo-stale.jsonl");
        assertEquals(1, countGuardEvents(f, "TODO_STALE"),
                "15 cycles of unrelated tool calls with '安装一批 Mods' still outstanding and no "
                        + "todo_write call at all must nudge exactly once");
        assertEquals("全部装完了。", finalText.get(),
                "the turn must still complete normally after the nudge, on the final scripted response");
    }
}
