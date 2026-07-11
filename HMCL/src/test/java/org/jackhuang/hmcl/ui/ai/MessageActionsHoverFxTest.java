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

import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;
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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.jackhuang.hmcl.ui.ai.AiMainPageFxTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Blueprint B6 / BF A2: a rendered message's bubble wrapper and its action bar are fused into
/// one `.ai-msg-block` VBox so the bar can hover-reveal via pure CSS (`.ai-bubble-actions`
/// opacity 0 -> 1 under `.ai-msg-block:hover`) with zero layout shift. Verified the BF A2 way:
/// `pseudoClassStateChanged(HOVER, true)` + `applyCss()`, no physical robot. Also guards the
/// C-04 red-line clause: a block holds EXACTLY the bubble wrapper and the action row — never a
/// subordinate (reasoning/tool) card.
public final class MessageActionsHoverFxTest {

    private static final PseudoClass HOVER = PseudoClass.getPseudoClass("hover");

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

    /// Like {@link AiMainPageFxTestSupport#showPage()}, but with root.css attached so the
    /// `.ai-bubble-actions` opacity rules actually apply (the shared helper shows a bare page).
    private static AIMainPage showStyledPage() throws Exception {
        AtomicReference<AIMainPage> ref = new AtomicReference<>();
        FxToolkit.setupSceneRoot(() -> {
            AIMainPage page = new AIMainPage();
            ref.set(page);
            StackPane root = new StackPane(page);
            root.setPrefSize(1100, 750);
            root.getStylesheets().add(Objects.requireNonNull(
                    MessageActionsHoverFxTest.class.getResource("/assets/css/root.css")).toExternalForm());
            return root;
        });
        FxToolkit.showStage();
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }

    @Test
    public void actionBarFadesInOnBlockHoverWithoutLayoutShift() throws Exception {
        AIMainPage page = showStyledPage();
        AiSessionStore store = (AiSessionStore) getField(page, "sessionStore");
        // Root cause of the historical flakiness: `sessionStore` is backed by the REAL,
        // per-workspace `ai-sessions.json` (SettingsManager.localConfigDirectory()), which every
        // other AI FX test class that constructs an AIMainPage also reads/writes in the same
        // suite run (and across separate test runs, via each AIMainPage's JVM-shutdown-hook
        // save() racing at JVM exit). `getCurrentSession()` could therefore return a session that
        // already carried leftover messages from an unrelated test/run, so "add 2 messages" did
        // not reliably mean "2 messages total" — createSession() always hands back a brand-new,
        // guaranteed-empty session, isolating this test from that ambient/shared state.
        AiSession session = store.createSession();
        assertNotNull(session);
        assertTrue(session.getMessages().isEmpty(), "a freshly created session must start empty");

        try {
            session.addMessage(new LlmMessage("user", "帮我看看这份日志"));
            session.addMessage(new LlmMessage("assistant", "日志里没有发现崩溃。"));

            WaitForAsyncUtils.asyncFx(() -> {
                try {
                    invoke(page, "loadSessionMessages", new Class<?>[]{AiSession.class}, session);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).get(10, TimeUnit.SECONDS);
            WaitForAsyncUtils.waitForFxEvents();

            VBox messageList = (VBox) getField(page, "messageList");
            List<Node> blocks = messageList.getChildren().stream()
                    .filter(n -> n.getStyleClass().contains("ai-msg-block"))
                    .toList();
            assertEquals(2, blocks.size(), "user + assistant message must each render as one .ai-msg-block");

            for (Node node : blocks) {
                VBox block = assertInstanceOf(VBox.class, node, "a .ai-msg-block is a VBox[wrapper, actions row]");

                // C-04 red-line clause: exactly bubble wrapper + action row, and no subordinate card.
                assertEquals(2, block.getChildren().size(), ".ai-msg-block must hold exactly bubble + action row");
                assertTrue(block.getChildren().get(0).getStyleClass().contains("ai-bubble-wrapper"),
                        "first block child must be the bubble wrapper");
                assertNull(findDescendant(block, n -> n.getStyleClass().contains("ai-tool-card")),
                        "subordinate cards must never be pulled into a .ai-msg-block (red line A1)");

                Node bar = findDescendant(block.getChildren().get(1),
                        n -> n.getStyleClass().contains("ai-bubble-actions"));
                assertNotNull(bar, "second block child must contain the .ai-bubble-actions bar");

                WaitForAsyncUtils.asyncFx(() -> {
                    block.applyCss();
                    assertEquals(0.0, bar.getOpacity(), 0.001, "action bar must be invisible until hover");
                    assertTrue(bar.isVisible() && bar.isManaged(),
                            "hover reveal is opacity-only — visible/managed stay true so layout never shifts");

                    block.pseudoClassStateChanged(HOVER, true);
                    block.applyCss();
                    assertEquals(1.0, bar.getOpacity(), 0.001, "action bar must fade in while the block is hovered");

                    block.pseudoClassStateChanged(HOVER, false);
                    block.applyCss();
                    assertEquals(0.0, bar.getOpacity(), 0.001, "action bar must fade back out when hover ends");
                }).get(10, TimeUnit.SECONDS);
                WaitForAsyncUtils.waitForFxEvents();
            }

            // VS §1.4 turn rhythm: the user block carries the extra 8px top margin (transferred from
            // the wrapper when the block was fused), the assistant block carries none.
            assertEquals(new Insets(8, 0, 0, 0), VBox.getMargin(blocks.get(0)),
                    "user message block must carry the 8px turn-separator top margin");
            assertNull(VBox.getMargin(((VBox) blocks.get(0)).getChildren().get(0)),
                    "the margin must move OFF the inner wrapper when it is fused into the block");
            assertNull(VBox.getMargin(blocks.get(1)), "assistant block must not carry a turn margin");
        } finally {
            // Don't leave this test's throwaway session sitting in the real, shared
            // ai-sessions.json for the next test class/run to trip over (see the createSession()
            // comment above) — delete it back out of the in-memory store. No explicit save() here:
            // AIMainPage's own JVM-shutdown hook flushes the store's final state on exit anyway.
            store.deleteSession(session.getId());
        }
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
