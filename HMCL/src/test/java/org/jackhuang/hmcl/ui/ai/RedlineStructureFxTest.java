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

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.AiSessionStore;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.jackhuang.hmcl.ui.ai.AiMainPageFxTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Red-line A1 structural regression gate (blueprint B4, reused by verification stage V5):
/// after rendering a full turn (reasoning card + tool card + assistant answer), the three
/// visuals must live in three DISTINCT direct children of `messageList` — flat siblings,
/// never nested inside any shared "turn container". The B4 subordinate-card wrappers are
/// each card's own single-node row, so the flat-sibling invariant must survive them too.
public final class RedlineStructureFxTest {

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
        System.setProperty("glass.win.uiScale", "100%");
        System.setProperty("prism.allowhidpi", "false");
        FxToolkit.registerPrimaryStage();
        useIsolatedConfigDirectory();
        ensureSettingsManagerLoaded();
        prepareFirstUseMarkers();
    }

    @AfterAll
    static void tearDownToolkit() throws Exception {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            FxToolkit.cleanupStages();
        }
        restoreRealConfigDirectory();
    }

    @BeforeEach
    void discardExceptionsLeakedByUnrelatedEarlierTests() {
        WaitForAsyncUtils.clearExceptions();
    }

    @Test
    public void reasoningToolAndAnswerAreFlatSiblingsOfMessageList() throws Exception {
        AIMainPage page = showPage();
        AiSessionStore store = (AiSessionStore) getField(page, "sessionStore");
        // A freshly created session is guaranteed empty — isolates this test from whatever the
        // ambient "current" session (auto-created by the constructor) happens to already contain
        // (see MessageActionsHoverFxTest for the exact ai-sessions.json pollution hazard this
        // avoids).
        AiSession session = store.createSession();
        try {
            // One persisted turn: user ask -> tool invocation record -> assistant answer w/ reasoning.
            LlmMessage.ToolPayload payload = new LlmMessage.ToolPayload();
            payload.name = "instance";
            payload.resultText = "已找到 3 个实例";
            payload.success = true;
            LlmMessage assistant = new LlmMessage("assistant", "已经装好了。");
            assistant.setReasoning("先确认实例，再安装光影。");
            session.addMessage(new LlmMessage("user", "帮我装光影"));
            session.addMessage(LlmMessage.toolRecord(payload, null));
            session.addMessage(assistant);

            WaitForAsyncUtils.asyncFx(() -> {
                try {
                    invoke(page, "loadSessionMessages", new Class<?>[]{AiSession.class}, session);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).get(10, TimeUnit.SECONDS);
            WaitForAsyncUtils.waitForFxEvents();

            VBox messageList = (VBox) getField(page, "messageList");
            int reasoningHost = soleHostIndex(messageList, n -> n instanceof ReasoningCard, "reasoning card");
            int toolHost = soleHostIndex(messageList, n -> n instanceof ToolCard, "tool card");
            int answerHost = soleHostIndex(messageList, n -> n.getStyleClass().contains("ai-bubble-ai"), "AI answer bubble");

            // A1: the three visuals are flat siblings — no two of them share a direct child
            // of messageList (a shared child would be a forbidden "turn container").
            assertNotEquals(reasoningHost, toolHost, "reasoning card and tool card must not share a container");
            assertNotEquals(toolHost, answerHost, "tool card and answer bubble must not share a container");
            assertNotEquals(reasoningHost, answerHost, "reasoning card and answer bubble must not share a container");

            // The two subordinate cards sit in single-purpose wrapper rows directly under
            // messageList (card -> wrapper -> messageList, nothing in between).
            Node reasoning = findDescendant(messageList.getChildren().get(reasoningHost),
                    n -> n instanceof ReasoningCard);
            Node tool = findDescendant(messageList.getChildren().get(toolHost),
                    n -> n instanceof ToolCard);
            assertSame(messageList, reasoning.getParent().getParent(),
                    "reasoning card must be exactly one wrapper away from messageList");
            assertSame(messageList, tool.getParent().getParent(),
                    "tool card must be exactly one wrapper away from messageList");
        } finally {
            store.deleteSession(session.getId());
        }
    }

    /// Index of the single direct child of {@code list} whose subtree contains a node matching
    /// {@code matcher}; fails the test when the node is missing or hosted by several children.
    private static int soleHostIndex(VBox list, Predicate<Node> matcher, String what) {
        int host = -1;
        for (int i = 0; i < list.getChildren().size(); i++) {
            if (findDescendant(list.getChildren().get(i), matcher) != null) {
                assertEquals(-1, host, what + " must appear under exactly one direct child of messageList");
                host = i;
            }
        }
        assertTrue(host >= 0, what + " must be rendered under messageList");
        return host;
    }

    private static Node findDescendant(Node root, Predicate<Node> matcher) {
        if (matcher.test(root)) {
            return root;
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = findDescendant(child, matcher);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
