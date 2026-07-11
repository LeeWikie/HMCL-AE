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
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.AiSessionStore;
import org.jackhuang.hmcl.ai.llm.LlmException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.TimeUnit;

import static org.jackhuang.hmcl.ui.ai.AiMainPageFxTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// B3 error display (A13 + 2-13/7.11): a failed response must render a structured error panel
/// (ai-bubble-error box) plus a retry row — an aligned HBox holding a native border button —
/// as the LAST messageList child, and firing retry must remove that row (the old code's
/// always-true `instanceof Parent` condition never removed the bare button).
/// Event/direct-method injection only (A7).
public final class AiErrorRetryFxTest {

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
    public void errorRendersPanelAndWorkingRetryRow() throws Exception {
        AIMainPage page = showPage();
        AiSessionStore store = (AiSessionStore) getField(page, "sessionStore");
        // Fresh, EMPTY session: retryLastTurn then finds no user turn and safely no-ops,
        // so firing the button only exercises the row-removal contract.
        invokeFx(page, "createSession");
        AiSession session = store.getCurrentSession();
        assertNotNull(session);

        VBox messageList = (VBox) getField(page, "messageList");
        int before = messageList.getChildren().size();
        int generation = (Integer) getField(page, "responseGeneration");

        WaitForAsyncUtils.asyncFx(() -> {
            try {
                invoke(page, "showAiError",
                        new Class<?>[]{Label.class, StringBuilder.class, LlmException.class,
                                AiSession.class, int.class},
                        null, new StringBuilder(), new LlmException("boom", 500), session, generation);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents(); // drain showAiError's own Platform.runLater

        var children = messageList.getChildren();
        assertTrue(children.size() > before, "the error path must append to the message list");

        // Last child = the retry row: an HBox holding a native jfx-button-border button.
        Node last = children.get(children.size() - 1);
        assertInstanceOf(HBox.class, last, "retry button must be wrapped in an aligned row");
        JFXButton retry = findFirstButton(last);
        assertNotNull(retry, "the retry row must contain the retry button");
        assertTrue(retry.getStyleClass().contains("jfx-button-border"),
                "retry is a native border button (C-12) — no ai-retry-btn ghost class");
        assertFalse(retry.getStyleClass().contains("ai-retry-btn"));
        assertNotNull(retry.getGraphic(), "retry carries the refresh icon");

        // The structured error panel exists somewhere above it.
        assertNotNull(findByStyleClass(messageList, "ai-bubble-error"),
                "the structured error panel (ai-bubble-error) must be rendered");

        // Firing retry removes its own row (and would resend the last user turn if one existed).
        WaitForAsyncUtils.asyncFx(retry::fire).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(messageList.getChildren().contains(last),
                "retry must remove its own row (7.11: the dead condition never removed it)");
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

    private static Node findByStyleClass(Node root, String styleClass) {
        if (root.getStyleClass().contains(styleClass)) {
            return root;
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = findByStyleClass(child, styleClass);
                if (found != null) return found;
            }
        }
        return null;
    }
}
