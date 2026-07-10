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

import com.jfoenix.controls.JFXButton;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ai.AiProviderProfile;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.jackhuang.hmcl.ui.ai.AiMainPageFxTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// B3 empty state (A14/C-16): without any usable model service the empty state must show the
/// "配置模型服务" CTA block and hide the suggestion chips; with one configured, the reverse.
/// Firing a chip must route the suggestion into the send pipeline (sendText → user bubble).
/// Event/direct-method injection only (A7).
public final class EmptyStateFxTest {

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

    @Test
    public void ctaAndChipsAreMutuallyExclusiveByProviderState() throws Exception {
        AIMainPage page = showPage();
        AiSettings settings = (AiSettings) getField(page, "aiSettings");
        List<AiProviderProfile> originalProfiles = settings.getProfiles();
        try {
            // No usable provider → CTA visible, chips hidden.
            WaitForAsyncUtils.asyncFx(() -> settings.setProfiles(List.of())).get(10, TimeUnit.SECONDS);
            invokeFx(page, "updateEmptyState");

            VBox suggestionsBox = (VBox) getField(page, "suggestionsBox");
            VBox noProviderBox = (VBox) getField(page, "noProviderBox");
            assertTrue(noProviderBox.isVisible() && noProviderBox.isManaged(),
                    "unconfigured: the CTA block must be shown");
            assertFalse(suggestionsBox.isVisible() || suggestionsBox.isManaged(),
                    "unconfigured: the suggestion chips must be hidden");
            assertNotNull(findButtonByText(noProviderBox, "配置模型服务"),
                    "the CTA block must contain the settings button");

            // One enabled provider WITH a cached model (setupModelSelector's own criterion, C-16)
            // → chips visible, CTA gone.
            AiProviderProfile profile = new AiProviderProfile();
            profile.setDisplayName("Test Provider");
            profile.setEnabled(true);
            profile.setCachedModels(List.of("test-model"));
            WaitForAsyncUtils.asyncFx(() -> settings.setProfiles(List.of(profile))).get(10, TimeUnit.SECONDS);
            invokeFx(page, "updateEmptyState");

            assertTrue(suggestionsBox.isVisible() && suggestionsBox.isManaged(),
                    "configured: the suggestion chips must be shown");
            assertFalse(noProviderBox.isVisible() || noProviderBox.isManaged(),
                    "configured: the CTA block must be hidden");
        } finally {
            WaitForAsyncUtils.asyncFx(() -> settings.setProfiles(originalProfiles)).get(10, TimeUnit.SECONDS);
            invokeFx(page, "updateEmptyState");
        }
    }

    /// Minimal do-nothing client so a REAL ChatAgent can be pre-cached and the chip's send can
    /// never reach a real network client factory.
    private static final class NoopClient implements AiChatClient {
        @Override
        public CompletableFuture<String> sendMessage(List<LlmMessage> messages) {
            return CompletableFuture.completedFuture("");
        }

        @Override
        public void sendMessageStreaming(List<LlmMessage> messages, LlmStreamCallback callback) {
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void firingAChipRoutesIntoTheSendPipeline() throws Exception {
        AIMainPage page = showPage();
        AiSettings settings = (AiSettings) getField(page, "aiSettings");
        AiSessionStore store = (AiSessionStore) getField(page, "sessionStore");
        // Fresh, empty session so the send below can't truncate/resend anything pre-existing.
        invokeFx(page, "createSession");
        AiSession session = store.getCurrentSession();
        assertNotNull(session);

        ChatAgent stub = new ChatAgent(new NoopClient(), session, settings,
                new AiPromptBuilder(settings, new ToolRegistry(), new SkillRegistry(), new AiSearchConfig()));
        Map<String, ChatAgent> cache = (Map<String, ChatAgent>) getField(page, "agentCache");
        VBox messageList = (VBox) getField(page, "messageList");
        int before = messageList.getChildren().size();
        try {
            cache.put(session.getId(), stub);

            JFXButton chip = firstChip(page);
            assertNotNull(chip, "empty state must contain suggestion chips");
            assertTrue(chip.getStyleClass().contains("jfx-button-border"),
                    "chips are native border buttons (C-09)");

            WaitForAsyncUtils.asyncFx(chip::fire).get(10, TimeUnit.SECONDS);
            WaitForAsyncUtils.waitForFxEvents();

            assertTrue(messageList.getChildren().size() > before,
                    "firing a chip must reach sendText — the suggestion appears as a user bubble");
            javafx.scene.control.TextArea input =
                    (javafx.scene.control.TextArea) getField(page, "inputField");
            assertEquals("", input.getText() == null ? "" : input.getText(),
                    "sendMessage consumes the chip text from the composer");
        } finally {
            invokeFx(page, "exitStreamingState");
            cache.remove(session.getId());
            stub.shutdown();
        }
    }

    /// The first suggestion chip: a JFXButton inside the suggestionsBox's FlowPane.
    private static JFXButton firstChip(AIMainPage page) throws Exception {
        VBox suggestionsBox = (VBox) getField(page, "suggestionsBox");
        return findFirstButton(suggestionsBox);
    }

    private static JFXButton findFirstButton(Node root) {
        if (root instanceof JFXButton btn) {
            return btn;
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                JFXButton found = findFirstButton(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JFXButton findButtonByText(Node root, String text) {
        if (root instanceof JFXButton btn && text.equals(btn.getText())) {
            return btn;
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                JFXButton found = findButtonByText(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
