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
package org.jackhuang.hmcl.ui.ai;

import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.AiSessionStore;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.agent.AiPromptBuilder;
import org.jackhuang.hmcl.ai.agent.ChatAgent;
import org.jackhuang.hmcl.ai.langchain4j.AiChatClient;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.ai.llm.LlmStreamCallback;
import org.jackhuang.hmcl.ai.search.AiSearchConfig;
import org.jackhuang.hmcl.ai.skills.SkillRegistry;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static org.jackhuang.hmcl.ui.ai.AiMainPageFxTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// P3 streaming guards (blueprint B1): ① settings changes must NOT shutdownNow the agent whose
/// turn is still streaming — its eviction is deferred to exitStreamingState (3a); ② deleting the
/// session that is currently generating must stop the stream properly first, so the Send button
/// can never be left dead (3b). Event/direct-method injection only (A7), no physical robot.
public final class StreamingGuardFxTest {

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
        System.setProperty("glass.win.uiScale", "100%");
        System.setProperty("prism.allowhidpi", "false");
        FxToolkit.registerPrimaryStage();
        ensureSettingsManagerLoaded();
        prepareFirstUseMarkers();
    }

    @AfterAll
    static void tearDownToolkit() throws Exception {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            FxToolkit.cleanupStages();
        }
    }

    @BeforeEach
    void discardExceptionsLeakedByUnrelatedEarlierTests() {
        WaitForAsyncUtils.clearExceptions();
    }

    /// Minimal do-nothing client so a REAL ChatAgent (final class — not mockable by subclassing)
    /// can be constructed; only its executor's shutdown state is observed.
    private static final class NoopClient implements AiChatClient {
        @Override
        public CompletableFuture<String> sendMessage(List<LlmMessage> messages) {
            return CompletableFuture.completedFuture("");
        }

        @Override
        public void sendMessageStreaming(List<LlmMessage> messages, LlmStreamCallback callback) {
        }
    }

    private static ChatAgent newStubAgent(AiSettings settings, AiSession session) {
        return new ChatAgent(new NoopClient(), session, settings,
                new AiPromptBuilder(settings, new ToolRegistry(), new SkillRegistry(), new AiSearchConfig()));
    }

    private static boolean isAgentExecutorShutdown(ChatAgent agent) throws Exception {
        return ((ExecutorService) getField(agent, "executor")).isShutdown();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void settingsChangeMidStreamDefersEvictionOfTheStreamingAgent() throws Exception {
        AIMainPage page = showPage();
        AiSettings settings = (AiSettings) getField(page, "aiSettings");
        AiSessionStore store = (AiSessionStore) getField(page, "sessionStore");
        AiSession session = store.getCurrentSession();
        assertNotNull(session);

        ChatAgent streamingAgent = newStubAgent(settings, session);
        ChatAgent idleAgent = newStubAgent(settings, session);
        Map<String, ChatAgent> cache = (Map<String, ChatAgent>) getField(page, "agentCache");
        Set<String> deferred = (Set<String>) getField(page, "deferredEvictions");
        try {
            WaitForAsyncUtils.asyncFx(() -> {
                try {
                    cache.put(session.getId(), streamingAgent);
                    cache.put("idle-session", idleAgent);
                    // Simulate a live stream owned by the current session.
                    setField(page, "streamSessionId", session.getId());
                    setField(page, "currentResponse", new CompletableFuture<Void>());
                    invoke(page, "clearAgentCache", new Class<?>[0]);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            WaitForAsyncUtils.waitForFxEvents();

            assertFalse(isAgentExecutorShutdown(streamingAgent),
                    "the STREAMING agent must not be shutdownNow'd mid-turn (3a)");
            assertTrue(cache.containsKey(session.getId()),
                    "the streaming agent must stay cached until its turn ends");
            assertTrue(deferred.contains(session.getId()),
                    "its eviction must be recorded for exitStreamingState");
            assertTrue(isAgentExecutorShutdown(idleAgent),
                    "a NON-streaming cached agent is still evicted immediately");
            assertFalse(cache.containsKey("idle-session"));

            // Turn ends → the deferred eviction is executed and the ledger cleared.
            invokeFx(page, "exitStreamingState");
            assertTrue(isAgentExecutorShutdown(streamingAgent),
                    "deferred agent must be shut down once the turn is over");
            assertFalse(cache.containsKey(session.getId()));
            assertTrue(deferred.isEmpty(), "deferredEvictions must be drained");
        } finally {
            streamingAgent.shutdown();
            idleAgent.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void deletingTheStreamingSessionStopsTheStreamAndFreesTheSendButton() throws Exception {
        AIMainPage page = showPage();
        AiSettings settings = (AiSettings) getField(page, "aiSettings");
        AiSessionStore store = (AiSessionStore) getField(page, "sessionStore");
        AiSession session = store.getCurrentSession();
        assertNotNull(session);

        ChatAgent agent = newStubAgent(settings, session);
        Map<String, ChatAgent> cache = (Map<String, ChatAgent>) getField(page, "agentCache");
        try {
            WaitForAsyncUtils.asyncFx(() -> {
                try {
                    cache.put(session.getId(), agent);
                    setField(page, "streamSessionId", session.getId());
                    setField(page, "currentResponse", new CompletableFuture<Void>());
                    invoke(page, "deleteSession", new Class<?>[]{String.class}, session.getId());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            WaitForAsyncUtils.waitForFxEvents();

            assertNull(getField(page, "currentResponse"),
                    "deleteSession on the streaming session must stop the stream — otherwise "
                            + "isStreaming() stays true forever and the Send button is dead (3b)");
            assertNull(getField(page, "streamSessionId"));
            assertTrue(isAgentExecutorShutdown(agent), "the deleted session's agent is evicted");
            assertFalse(cache.containsKey(session.getId()));
        } finally {
            agent.shutdown();
        }
    }
}
