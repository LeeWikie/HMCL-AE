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
import org.jackhuang.hmcl.ai.tools.ToolConfirmHandler;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// End-to-end regression test for the terminal-denial short circuit (borrow-list A1): once the
/// user declines a dangerous operation at the confirmation prompt, re-issuing the SAME operation
/// — even with the argument fields re-ordered and the model-facing `description` reworded, which
/// used to reset the raw-JSON dup fingerprint — must be refused pre-execution WITHOUT showing the
/// confirmation dialog again. Before this fix the user was re-prompted for the identical
/// operation until DUP_CALL_LIMIT (3) finally tripped.
public final class TerminalDenialLoopTest {

    /// A DANGEROUS_WRITE stub whose executions are counted — it must never run in this test.
    private static final class DangerousWipeTool implements ToolSpec {
        final AtomicInteger executions = new AtomicInteger();
        @Override public String getName() { return "wipe"; }
        @Override public String getDescription() { return "dangerous wipe (test stub)"; }
        @Override public ToolPermission getPermission(Map<String, Object> parameters) {
            return ToolPermission.DANGEROUS_WRITE;
        }
        @Override public ToolResult execute(Map<String, Object> parameters) {
            executions.incrementAndGet();
            return ToolResult.success("wiped");
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

    private static ChatResponse text(String t) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(t))
                .finishReason(FinishReason.STOP)
                .tokenUsage(new TokenUsage(120, 20))
                .build();
    }

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
    public void declinedOperationIsShortCircuitedOnRepeatWithoutRePrompting() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        DangerousWipeTool tool = new DangerousWipeTool();
        registry.register(tool);

        AtomicInteger prompts = new AtomicInteger();
        ToolConfirmHandler decline = (name, summary) -> { prompts.incrementAndGet(); return false; };
        // Dangerous confirmation ON: the DANGEROUS_WRITE call resolves to ASK and the user says no.
        LangChain4jToolAdapter toolAdapter = new LangChain4jToolAdapter(
                registry, new AiExecutionPolicy(AiApprovalMode.AUTO, true), decline, null);

        StreamingChatModel streaming = new FakeStreamingModel(
                // cycle 0: the model asks for the dangerous call — user declines at the prompt.
                toolCall("wipe", "{\"target\":\"OldPack\",\"confirm\":true,\"description\":\"清理旧实例\"}"),
                // cycle 1: the model re-issues the SAME operation, fields re-ordered and the
                // description reworded (the classic fingerprint-evading perturbation).
                toolCall("wipe", "{\"description\":\"换个说法再试一次\",\"confirm\":true,\"target\":\"OldPack\"}"),
                // cycle 2: final text answer.
                text("好的，那我不动它了。"));

        LangChain4jChatAdapter adapter = new LangChain4jChatAdapter(new FakeChatModel(), streaming, toolAdapter);

        List<String> resultSummaries = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        adapter.sendMessageStreaming(
                List.of(new LlmMessage("system", "sys"), new LlmMessage("user", "删掉旧实例")),
                new LlmStreamCallback() {
                    @Override public void onToken(String t) { }
                    @Override public void onUsage(LlmUsage u) { }
                    @Override public void onToolActivity(String name, String args) { }
                    @Override public void onToolResult(String name, boolean success, String summary) {
                        resultSummaries.add(summary);
                    }
                    @Override public void onComplete(String content) { done.countDown(); }
                    @Override public void onError(LlmException e) { done.countDown(); }
                });
        assertTrue(done.await(10, TimeUnit.SECONDS), "turn completed");

        assertEquals(1, prompts.get(),
                "the user must be prompted exactly ONCE — the repeat must be short-circuited "
                        + "pre-execution, never re-opening the confirmation dialog");
        assertEquals(0, tool.executions.get(), "the declined tool must never actually run");
        assertEquals(2, resultSummaries.size(), "both calls still produce a result for the model");
        assertTrue(resultSummaries.get(0).contains("declined"),
                "cycle 0's result is the decline: " + resultSummaries.get(0));
        assertTrue(resultSummaries.get(1).contains("terminal_denial"),
                "cycle 1's result must be the terminal-denial short circuit riding the "
                        + "runtime-guard channel: " + resultSummaries.get(1));
        assertTrue(resultSummaries.get(1).contains("already declined"),
                "the short-circuit must say the operation was already declined: " + resultSummaries.get(1));
    }
}
