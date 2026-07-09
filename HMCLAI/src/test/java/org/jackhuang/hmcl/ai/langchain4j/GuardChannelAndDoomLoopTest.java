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
package org.jackhuang.hmcl.ai.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
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
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// End-to-end coverage for the runtime-guard identity channel (H2 / borrow-list A3) and the
/// doom-loop hard stop's precise attribution + true force-finish (H4 / borrow-list A2):
///   - every guard nudge injected mid-turn must arrive wrapped in `<runtime-guard type="...">`,
///     never as a bare user-said sentence;
///   - after {@code LOOP_SIGNATURE_HARD_STOP} identical calls the turn must REALLY end — one
///     final request with NO tool specifications, doom-loop-specific wording, and zero further
///     tool executions.
public final class GuardChannelAndDoomLoopTest {

    /// READ_ONLY echo whose result is constant for a given query — repeated identical calls
    /// produce identical loop signatures, driving the soft warnings and the hard stop.
    private static final class EchoTool implements ToolSpec {
        final AtomicInteger executions = new AtomicInteger();
        @Override public String getName() { return "probe"; }
        @Override public String getDescription() { return "read-only echo (test stub)"; }
        @Override public ToolPermission getPermission() { return ToolPermission.READ_ONLY; }
        @Override public ToolResult execute(Map<String, Object> parameters) {
            executions.incrementAndGet();
            return ToolResult.success("probed:" + parameters.get("query"));
        }
    }

    private static ChatResponse toolCall(String args) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.builder()
                        .toolExecutionRequests(List.of(
                                ToolExecutionRequest.builder().id("c1").name("probe").arguments(args).build()))
                        .build())
                .finishReason(FinishReason.TOOL_EXECUTION)
                .tokenUsage(new TokenUsage(100, 10))
                .build();
    }

    private static ChatResponse text(String t) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(t))
                .finishReason(FinishReason.STOP)
                .tokenUsage(new TokenUsage(120, 20))
                .build();
    }

    /// Streaming fake that also RECORDS every request it receives, so tests can inspect what the
    /// model would actually have seen (guard wrapping, tool availability).
    private static final class RecordingStreamingModel implements StreamingChatModel {
        final List<ChatRequest> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        private final Queue<ChatResponse> scripted;
        RecordingStreamingModel(List<ChatResponse> rs) { scripted = new ArrayDeque<>(rs); }
        @Override public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            requests.add(request);
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

    /// Collects the text of every user-role message across all recorded requests.
    private static List<String> allUserMessageTexts(List<ChatRequest> requests) {
        List<String> texts = new ArrayList<>();
        for (ChatRequest r : requests) {
            for (ChatMessage m : r.messages()) {
                if (m instanceof UserMessage u) {
                    texts.add(u.singleText());
                }
            }
        }
        return texts;
    }

    @Test
    public void doomLoopHardStopsWithGuardWrappedAttributionAndNoTools() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        EchoTool tool = new EchoTool();
        registry.register(tool);
        LangChain4jToolAdapter toolAdapter = new LangChain4jToolAdapter(
                registry, new AiExecutionPolicy(AiApprovalMode.AUTO, false), null, null);

        // 6 identical calls (LOOP_SIGNATURE_HARD_STOP) then the model's forced closing text.
        List<ChatResponse> scripted = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            scripted.add(toolCall("{\"query\":\"same\"}"));
        }
        scripted.add(text("我卡住了：反复查同一个东西没有新结果。"));
        RecordingStreamingModel streaming = new RecordingStreamingModel(scripted);

        LangChain4jChatAdapter adapter = new LangChain4jChatAdapter(new FakeChatModel(), streaming, toolAdapter);

        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> finalText = new java.util.concurrent.atomic.AtomicReference<>();
        adapter.sendMessageStreaming(
                List.of(new LlmMessage("system", "sys"), new LlmMessage("user", "帮我查个东西")),
                new LlmStreamCallback() {
                    @Override public void onToken(String t) { }
                    @Override public void onUsage(LlmUsage u) { }
                    @Override public void onToolActivity(String name, String args) { }
                    @Override public void onToolResult(String name, boolean success, String summary) { }
                    @Override public void onComplete(String content) { finalText.set(content); done.countDown(); }
                    @Override public void onError(LlmException e) { done.countDown(); }
                });
        assertTrue(done.await(10, TimeUnit.SECONDS), "turn completed");

        assertEquals(6, tool.executions.get(),
                "execution must stop at the hard-stop threshold — no 7th call ever runs");
        assertEquals(7, streaming.requests.size(),
                "6 tool cycles + exactly one final, forced text-only closing request");

        ChatRequest closing = streaming.requests.get(streaming.requests.size() - 1);
        assertTrue(closing.toolSpecifications() == null || closing.toolSpecifications().isEmpty(),
                "the forced closing request must offer NO tools — the stop is real, not advisory");

        List<String> userTexts = allUserMessageTexts(List.of(closing));
        String forceFinish = userTexts.stream()
                .filter(t -> t.contains("<" + GuardMessageFormatter.TAG + " type=\"force_finish\">"))
                .findFirst().orElse(null);
        assertNotNull(forceFinish, "the closing instruction must ride the runtime-guard channel");
        assertTrue(forceFinish.contains("原地打转"),
                "the doom-loop stop must be attributed to the loop, not to the cycle budget: " + forceFinish);
        assertFalse(forceFinish.contains("已连续调用工具"),
                "must NOT use the cycle-cap wording for a doom-loop stop");

        // The escalating soft warnings (repeats 3/4/5) must also have ridden the guard channel.
        long softWrapped = allUserMessageTexts(streaming.requests).stream()
                .distinct()
                .filter(t -> t.contains("<" + GuardMessageFormatter.TAG + " type=\"loop_warning\">"))
                .count();
        assertEquals(3, softWrapped, "three distinct escalating soft warnings, each guard-wrapped");

        assertEquals("我卡住了：反复查同一个东西没有新结果。", finalText.get(),
                "the model's own closing summary is the turn's final text");
    }

    @Test
    public void noProgressNudgeRidesTheGuardChannel() throws Exception {
        // A tool that always fails with a DIFFERENT message (digits vary per call would be blanked;
        // vary letters instead) — signatures stay unique so the loop-signature guard never fires,
        // isolating the NO_PROGRESS streak guard (6 consecutive failures).
        final class FailingTool implements ToolSpec {
            int n = 0;
            @Override public String getName() { return "flaky"; }
            @Override public String getDescription() { return "always fails differently (test stub)"; }
            @Override public ToolPermission getPermission() { return ToolPermission.READ_ONLY; }
            @Override public ToolResult execute(Map<String, Object> parameters) {
                n++;
                return ToolResult.failure("distinct failure " + "abcdefgh".charAt(n % 8));
            }
        }
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FailingTool());
        LangChain4jToolAdapter toolAdapter = new LangChain4jToolAdapter(
                registry, new AiExecutionPolicy(AiApprovalMode.AUTO, false), null, null);

        List<ChatResponse> scripted = new ArrayList<>();
        for (char c : "abcdef".toCharArray()) {
            scripted.add(toolCall("{\"query\":\"q" + c + "\"}"));
        }
        scripted.add(text("确实一直失败，我先停下。"));
        RecordingStreamingModel streaming = new RecordingStreamingModel(scripted);
        LangChain4jChatAdapter adapter = new LangChain4jChatAdapter(new FakeChatModel(), streaming, toolAdapter);

        CountDownLatch done = new CountDownLatch(1);
        adapter.sendMessageStreaming(
                List.of(new LlmMessage("system", "sys"), new LlmMessage("user", "试试看")),
                new LlmStreamCallback() {
                    @Override public void onToken(String t) { }
                    @Override public void onUsage(LlmUsage u) { }
                    @Override public void onToolActivity(String name, String args) { }
                    @Override public void onToolResult(String name, boolean success, String summary) { }
                    @Override public void onComplete(String content) { done.countDown(); }
                    @Override public void onError(LlmException e) { done.countDown(); }
                });
        assertTrue(done.await(10, TimeUnit.SECONDS), "turn completed");

        boolean sawWrappedNoProgress = allUserMessageTexts(streaming.requests).stream()
                .anyMatch(t -> t.contains("<" + GuardMessageFormatter.TAG + " type=\"no_progress\">")
                        && t.contains("[系统提示]"));
        assertTrue(sawWrappedNoProgress,
                "after 6 consecutive non-progress results the nudge must arrive guard-wrapped");
    }
}
