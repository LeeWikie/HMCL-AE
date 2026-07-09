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
package org.jackhuang.hmcl.ai.agent;

import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.langchain4j.AiChatClient;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.ai.llm.LlmStreamCallback;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/// Regression tests for {@link ChatAgent#compact()} — in particular that compaction keeps the
/// RAW content (including {@link LlmMessage.ToolPayload}) of the most recent turns instead of
/// collapsing the entire history into the summary alone.
public final class ChatAgentCompactTest {

    /// A turn as added by the real streaming path: a user message, one tool record (with a
    /// distinguishing {@link LlmMessage.ToolPayload}), and an assistant reply.
    private static void addTurn(AiSession session, String label) {
        session.addMessage(new LlmMessage("user", "用户消息-" + label));
        LlmMessage.ToolPayload payload = new LlmMessage.ToolPayload();
        payload.name = "tool-" + label;
        payload.argsJson = "{\"arg\":\"" + label + "\"}";
        payload.resultText = "raw-tool-result-" + label;
        payload.success = true;
        session.addMessage(LlmMessage.toolRecord(payload, "turn-" + label));
        session.addMessage(new LlmMessage("assistant", "助手回复-" + label));
    }

    /// Fake {@link AiChatClient} that returns a fixed, recognisable summary text containing NONE
    /// of the raw per-turn markers, so any raw marker found in the post-compaction session can
    /// only have survived via the kept-turn tail, never by accidentally reappearing in the summary.
    private static final class FixedSummaryClient implements AiChatClient {
        private final String summary;
        private List<LlmMessage> lastRequest;

        FixedSummaryClient(String summary) {
            this.summary = summary;
        }

        @Override
        public CompletableFuture<String> sendMessage(List<LlmMessage> messages) {
            this.lastRequest = messages;
            return CompletableFuture.completedFuture(summary);
        }

        @Override
        public void sendMessageStreaming(List<LlmMessage> messages, LlmStreamCallback callback) {
            throw new UnsupportedOperationException("not used by compact()");
        }
    }

    private static ChatAgent buildAgent(AiChatClient client, AiSession session, Path dir) throws IOException {
        AiSettings settings = new AiSettings(dir);
        return new ChatAgent(client, session, settings, null);
    }

    /// With more history than {@code COMPACT_KEEP_TURNS} (2), compaction must: (1) replace the
    /// earlier turns with the summary message only, and (2) re-append the LAST TWO turns verbatim
    /// — same tool name/args/result text and assistant reply content, not merely "some message
    /// exists" but the exact original strings.
    @Test
    public void testCompactKeepsRawTailOfLastTwoTurns() throws IOException {
        Path dir = Files.createTempDirectory("hmcl-ai-compact-");
        try {
            AiSession session = new AiSession();
            addTurn(session, "1");
            addTurn(session, "2");
            addTurn(session, "3");
            addTurn(session, "4");
            assertEquals(12, session.size(), "sanity: 4 turns x 3 messages each");

            FixedSummaryClient client = new FixedSummaryClient("摘要：仅描述早期回合，不包含逐字标记。");
            ChatAgent agent = buildAgent(client, session, dir);

            String summary = agent.compact().join();
            assertEquals("摘要：仅描述早期回合，不包含逐字标记。", summary);

            List<LlmMessage> after = session.getMessages();
            // summary message + 2 kept turns (3 messages each) = 7
            assertEquals(7, after.size(), "expected summary message + last 2 raw turns");

            LlmMessage summaryMessage = after.get(0);
            assertEquals("assistant", summaryMessage.getRole());
            assertTrue(summaryMessage.getContent().contains("【上下文已压缩】"));
            assertTrue(summaryMessage.getContent().contains(summary));

            // Turns 1 and 2 must NOT survive anywhere in the post-compaction session.
            String wholeSession = renderAll(after);
            assertFalse(wholeSession.contains("用户消息-1"), "turn 1 user message should be summarised away");
            assertFalse(wholeSession.contains("raw-tool-result-1"), "turn 1 tool result should be summarised away");
            assertFalse(wholeSession.contains("助手回复-1"), "turn 1 assistant reply should be summarised away");
            assertFalse(wholeSession.contains("用户消息-2"), "turn 2 user message should be summarised away");
            assertFalse(wholeSession.contains("raw-tool-result-2"), "turn 2 tool result should be summarised away");
            assertFalse(wholeSession.contains("助手回复-2"), "turn 2 assistant reply should be summarised away");

            // Turns 3 and 4 must survive VERBATIM, including the raw ToolPayload fields.
            LlmMessage user3 = after.get(1);
            assertEquals("user", user3.getRole());
            assertEquals("用户消息-3", user3.getContent());

            LlmMessage tool3 = after.get(2);
            assertTrue(tool3.isToolRecord());
            assertNotNull(tool3.getToolPayload());
            assertEquals("tool-3", tool3.getToolPayload().name);
            assertEquals("{\"arg\":\"3\"}", tool3.getToolPayload().argsJson);
            assertEquals("raw-tool-result-3", tool3.getToolPayload().resultText);
            assertTrue(tool3.getToolPayload().success);

            LlmMessage assistant3 = after.get(3);
            assertEquals("assistant", assistant3.getRole());
            assertEquals("助手回复-3", assistant3.getContent());

            LlmMessage user4 = after.get(4);
            assertEquals("用户消息-4", user4.getContent());

            LlmMessage tool4 = after.get(5);
            assertTrue(tool4.isToolRecord());
            assertNotNull(tool4.getToolPayload());
            assertEquals("tool-4", tool4.getToolPayload().name);
            assertEquals("{\"arg\":\"4\"}", tool4.getToolPayload().argsJson);
            assertEquals("raw-tool-result-4", tool4.getToolPayload().resultText);

            LlmMessage assistant4 = after.get(6);
            assertEquals("助手回复-4", assistant4.getContent());

            // The summarisation request itself must still have seen the FULL history (minus tool
            // records) so the summary can describe the whole conversation, not just the tail.
            String requestText = renderAll(client.lastRequest);
            assertTrue(requestText.contains("用户消息-1"), "summarisation request should include early turns");
            assertTrue(requestText.contains("用户消息-4"), "summarisation request should include the latest turn");
        } finally {
            cleanup(dir);
        }
    }

    /// When the history has fewer turns than {@code COMPACT_KEEP_TURNS}, there is nothing to
    /// summarise away — the whole history is kept raw, appended after the summary.
    @Test
    public void testCompactWithFewerThanKeepTurnsKeepsEverythingRaw() throws IOException {
        Path dir = Files.createTempDirectory("hmcl-ai-compact-small-");
        try {
            AiSession session = new AiSession();
            addTurn(session, "only");

            FixedSummaryClient client = new FixedSummaryClient("摘要：单回合。");
            ChatAgent agent = buildAgent(client, session, dir);

            agent.compact().join();

            List<LlmMessage> after = session.getMessages();
            assertEquals(4, after.size(), "summary message + the single raw turn (3 messages)");
            assertEquals("用户消息-only", after.get(1).getContent());
            assertEquals("raw-tool-result-only", after.get(2).getToolPayload().resultText);
            assertEquals("助手回复-only", after.get(3).getContent());
        } finally {
            cleanup(dir);
        }
    }

    /// Compacting an empty session must remain a no-op (documented contract, unaffected by the
    /// raw-tail change).
    @Test
    public void testCompactEmptySessionIsNoop() throws IOException {
        Path dir = Files.createTempDirectory("hmcl-ai-compact-empty-");
        try {
            AiSession session = new AiSession();
            FixedSummaryClient client = new FixedSummaryClient("不应被调用");
            ChatAgent agent = buildAgent(client, session, dir);

            String summary = agent.compact().join();
            assertEquals("", summary);
            assertEquals(0, session.size());
        } finally {
            cleanup(dir);
        }
    }

    private static String renderAll(List<LlmMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (LlmMessage m : messages) {
            sb.append(m.getContent()).append('\n');
            if (m.getToolPayload() != null) {
                sb.append(m.getToolPayload().argsJson).append('\n')
                        .append(m.getToolPayload().resultText).append('\n');
            }
        }
        return sb.toString();
    }

    private static void cleanup(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                    });
        } catch (IOException ignored) {
        }
    }
}
