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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// H4（第二波·弹窗侧契约）: the doom-loop hard stop now consults the UI-injected
/// {@link LangChain4jChatAdapter.LoopHardStopConfirmer} BEFORE force-finishing the turn.
/// "继续" clears the signature window and hands the model another cycle; "否" (and the
/// no-UI/null default, covered by {@code GuardChannelAndDoomLoopTest}) keeps the hard stop.
public final class LoopHardStopConfirmerTest {

    @AfterEach
    void clearStaticConfirmer() {
        LangChain4jChatAdapter.setLoopHardStopConfirmer(null);
    }

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

    private static String runTurn(RecordingStreamingModel streaming, EchoTool tool) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        LangChain4jToolAdapter toolAdapter = new LangChain4jToolAdapter(
                registry, new AiExecutionPolicy(AiApprovalMode.AUTO, false), null, null);
        LangChain4jChatAdapter adapter = new LangChain4jChatAdapter(new FakeChatModel(), streaming, toolAdapter);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> finalText = new AtomicReference<>();
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
        return finalText.get();
    }

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
    public void userApprovedContinueClearsTheWindowAndHandsBackTheReins() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        AtomicReference<String> askedTool = new AtomicReference<>();
        AtomicInteger askedRepeats = new AtomicInteger();
        LangChain4jChatAdapter.setLoopHardStopConfirmer((toolName, repeatCount) -> {
            asked.incrementAndGet();
            askedTool.set(toolName);
            askedRepeats.set(repeatCount);
            return true; // 用户选择“继续”
        });

        // 6 identical calls trip the hard stop → user approves → ONE more identical call runs
        // (signature window was cleared, so it's the window's first entry) → model wraps up.
        List<ChatResponse> scripted = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            scripted.add(toolCall("{\"query\":\"same\"}"));
        }
        scripted.add(text("好了，这次拿到结果了。"));
        RecordingStreamingModel streaming = new RecordingStreamingModel(scripted);
        EchoTool tool = new EchoTool();

        String finalText = runTurn(streaming, tool);

        assertEquals(1, asked.get(), "the user must be asked exactly once at the hard stop");
        assertEquals("probe", askedTool.get());
        assertEquals(6, askedRepeats.get(), "the confirm must carry the observed repeat count");
        assertEquals(7, tool.executions.get(),
                "user-approved continue must hand the model another cycle (a 7th call runs)");
        assertEquals("好了，这次拿到结果了。", finalText);

        // The continuation must ride the guard identity channel, and NO force-finish must occur.
        List<String> userTexts = allUserMessageTexts(streaming.requests);
        assertTrue(userTexts.stream().anyMatch(t ->
                        t.contains("<" + GuardMessageFormatter.TAG + " type=\"loop_override\">")
                        && t.contains("用户确认允许你继续")),
                "the user's go-ahead must be injected via the runtime-guard channel");
        assertTrue(userTexts.stream().noneMatch(t ->
                        t.contains("<" + GuardMessageFormatter.TAG + " type=\"force_finish\">")),
                "no force-finish instruction may be issued when the user approved continuing");
        // Every request after the override still offers tools (the stop was rescinded).
        ChatRequest last = streaming.requests.get(streaming.requests.size() - 1);
        assertTrue(last.toolSpecifications() != null && !last.toolSpecifications().isEmpty(),
                "tools stay available after a user-approved continue");
    }

    @Test
    public void userDeclinedContinueKeepsTheForceFinish() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        LangChain4jChatAdapter.setLoopHardStopConfirmer((toolName, repeatCount) -> {
            asked.incrementAndGet();
            return false; // 用户选择“否”（或超时/Stop 自动否）
        });

        List<ChatResponse> scripted = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            scripted.add(toolCall("{\"query\":\"same\"}"));
        }
        scripted.add(text("我卡住了，先总结。"));
        RecordingStreamingModel streaming = new RecordingStreamingModel(scripted);
        EchoTool tool = new EchoTool();

        String finalText = runTurn(streaming, tool);

        assertEquals(1, asked.get(), "the user must still be asked exactly once");
        assertEquals(6, tool.executions.get(), "declining keeps the hard stop — no 7th call");
        assertEquals("我卡住了，先总结。", finalText);

        ChatRequest closing = streaming.requests.get(streaming.requests.size() - 1);
        assertTrue(closing.toolSpecifications() == null || closing.toolSpecifications().isEmpty(),
                "the forced closing request must offer NO tools");
        assertTrue(allUserMessageTexts(List.of(closing)).stream().anyMatch(t ->
                        t.contains("<" + GuardMessageFormatter.TAG + " type=\"force_finish\">")),
                "declining must fall through to the original force-finish path");
    }

    @Test
    public void aThrowingConfirmerIsTreatedAsDecline() throws Exception {
        LangChain4jChatAdapter.setLoopHardStopConfirmer((toolName, repeatCount) -> {
            throw new IllegalStateException("dialog machinery exploded");
        });
        List<ChatResponse> scripted = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            scripted.add(toolCall("{\"query\":\"same\"}"));
        }
        scripted.add(text("收尾。"));
        RecordingStreamingModel streaming = new RecordingStreamingModel(scripted);
        EchoTool tool = new EchoTool();

        runTurn(streaming, tool);
        assertEquals(6, tool.executions.get(), "a broken confirmer must never unlock extra cycles");
        ChatRequest closing = streaming.requests.get(streaming.requests.size() - 1);
        assertTrue(closing.toolSpecifications() == null || closing.toolSpecifications().isEmpty());
    }
}
