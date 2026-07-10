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

import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.LlmConfig;
import org.jackhuang.hmcl.ai.langchain4j.AiChatClient;
import org.jackhuang.hmcl.ai.llm.LlmException;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.ai.llm.LlmStreamCallback;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for the connection-test optimisations in {@link ChatAgentFactory}:
/// reasoning defaults to `"none"` on the first attempt, a single no-reasoning
/// ({@code null}) fallback on {@link LlmException}, and "non-empty reply = connected"
/// (no content matching). All tests inject a stub client through the package-private
/// {@link ChatAgentFactory#testClientFactory} seam — no live endpoint is contacted.
public final class ChatAgentFactoryTestConnectionTest {

    /// Records the reasoning-effort of every config the factory was asked to build a client for.
    private final List<@Nullable String> reasoningEfforts = new ArrayList<>();

    @AfterEach
    public void tearDown() {
        ChatAgentFactory.testClientFactory = null;
    }

    /// Installs a stub factory that records each config's reasoning effort and returns
    /// a client whose single response is produced by {@code responder} (per-attempt, 0-indexed).
    private void install(Function<Integer, CompletableFuture<@Nullable String>> responder) {
        int[] attempt = {0};
        ChatAgentFactory.testClientFactory = config -> {
            reasoningEfforts.add(config.getReasoningEffort());
            int i = attempt[0]++;
            return new StubClient(responder.apply(i));
        };
    }

    private static AiSettings freshSettings() throws IOException {
        // The stub factory bypasses resolveClient, so the settings' endpoint / key /
        // model / provider values never reach a real client — defaults are sufficient.
        Path dir = Files.createTempDirectory("hmcl-ai-testconn-");
        AiSettings settings = new AiSettings(dir);
        DIRS_TO_CLEAN.add(dir);
        return settings;
    }

    private static final List<Path> DIRS_TO_CLEAN = new ArrayList<>();

    @AfterEach
    public void cleanupDirs() {
        for (Path dir : DIRS_TO_CLEAN) {
            try {
                Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            } catch (IOException ignored) { }
        }
        DIRS_TO_CLEAN.clear();
    }

    /// First attempt must carry reasoningEffort="none"; a successful non-empty reply
    /// means connected and no fallback attempt is made.
    @Test
    public void testFirstAttemptUsesNoneAndSucceeds() throws Exception {
        install(i -> CompletableFuture.completedFuture("通"));
        AiSettings settings = freshSettings();

        String reply = ChatAgentFactory.testConnectionSync(settings, 5);

        assertEquals(1, reasoningEfforts.size(), "success should need only one attempt");
        assertEquals("none", reasoningEfforts.get(0), "first attempt must disable reasoning");
        assertEquals("通", reply);
    }

    /// A reply whose content is not "通" (model said more) still counts as connected —
    /// no strict content matching.
    @Test
    public void testNonEmptyNonMatchingReplyIsSuccess() throws Exception {
        install(i -> CompletableFuture.completedFuture("Hello, I am connected!"));
        AiSettings settings = freshSettings();

        String reply = ChatAgentFactory.testConnectionSync(settings, 5);

        assertEquals(1, reasoningEfforts.size());
        assertEquals("Hello, I am connected!", reply);
    }

    /// First attempt (none) fails with an LlmException → exactly one retry with
    /// reasoning omitted (null); the retry's success is returned.
    @Test
    public void testLlmExceptionTriggersNullReasoningRetry() throws Exception {
        install(i -> i == 0
                ? CompletableFuture.failedFuture(new LlmException("reasoning not supported", 400))
                : CompletableFuture.completedFuture("通"));
        AiSettings settings = freshSettings();

        String reply = ChatAgentFactory.testConnectionSync(settings, 5);

        assertEquals(2, reasoningEfforts.size(), "should retry exactly once");
        assertEquals("none", reasoningEfforts.get(0), "first attempt disables reasoning");
        assertNull(reasoningEfforts.get(1), "retry must omit the reasoning parameter");
        assertEquals("通", reply);
    }

    /// Both attempts fail → the (second) LlmException is surfaced, and no third attempt happens.
    @Test
    public void testBothAttemptsFailPropagates() throws IOException {
        install(i -> CompletableFuture.failedFuture(
                new LlmException("attempt-" + i + "-failed", 401)));
        AiSettings settings = freshSettings();

        LlmException ex = assertThrows(LlmException.class,
                () -> ChatAgentFactory.testConnectionSync(settings, 5));

        assertEquals(2, reasoningEfforts.size(), "exactly two attempts before giving up");
        assertEquals("attempt-1-failed", ex.getMessage(), "the retry's error should surface");
    }

    /// The explicit-endpoint sync overload must share the same behaviour: first none,
    /// retry null on LlmException.
    @Test
    public void testExplicitEndpointOverloadSharesFallback() throws Exception {
        install(i -> i == 0
                ? CompletableFuture.failedFuture(new LlmException("reasoning rejected", 400))
                : CompletableFuture.completedFuture("通"));

        String reply = ChatAgentFactory.testConnectionSync(
                "https://example.invalid/v1/chat/completions", "k", "stub-model", "openai", 5);

        assertEquals(2, reasoningEfforts.size());
        assertEquals("none", reasoningEfforts.get(0));
        assertNull(reasoningEfforts.get(1));
        assertEquals("通", reply);
    }

    /// Explicit-endpoint overload succeeds on the first (none) attempt, mirroring the
    /// settings-based overload — the two variants stay consistent.
    @Test
    public void testExplicitEndpointOverloadSucceedsOnNone() throws Exception {
        install(i -> CompletableFuture.completedFuture("通"));

        String reply = ChatAgentFactory.testConnectionSync(
                "https://example.invalid/v1/chat/completions", "k", "stub-model", "openai", 5);

        assertEquals(1, reasoningEfforts.size());
        assertEquals("none", reasoningEfforts.get(0));
        assertEquals("通", reply);
    }

    /// Minimal stub returning a single preset future for {@link #sendMessage}.
    private static final class StubClient implements AiChatClient {
        private final CompletableFuture<@Nullable String> response;

        StubClient(CompletableFuture<@Nullable String> response) {
            this.response = response;
        }

        @Override
        public CompletableFuture<@Nullable String> sendMessage(List<LlmMessage> messages) {
            return response;
        }

        @Override
        public void sendMessageStreaming(List<LlmMessage> messages, LlmStreamCallback callback) {
            throw new UnsupportedOperationException("streaming not used in connection test");
        }
    }
}
