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

import javafx.scene.control.ScrollPane;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ai.AiSettings;
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

/// Pins the three chat auto-scroll fixes (2026-07-11 反馈, autoscroll-fix-spec.md), driving a REAL
/// {@link AIMainPage} through TestFX:
///  #1 sending a message re-pins to the bottom even after the user scrolled up — through a path that
///     ignores BOTH the stickToBottom flag and the autoScroll toggle (that toggle only governs
///     streaming auto-follow, not "show me the message I just typed");
///  #2 while pinned, a streaming append follows to the bottom;
///  #3 while detached (user reading history), a streaming append must NOT yank the view back down;
///  + the wheel-up gesture detaches stick-to-bottom.
/// The scrollbar-thumb-drag half of the detection depends on a built skin and live pointer state and
/// is verified on a real machine (JavaFX can't be screenshotted here) — these cover the wheel +
/// send + gating logic that IS unit-testable.
public final class AutoScrollFxTest {

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

    /// Fills the message view past its viewport (so vvalue can actually travel between 0 and 1) and
    /// leaves it scrolled to the bottom.
    private static void overflowAndPinBottom(AIMainPage page, ScrollPane scrollPane) throws Exception {
        WaitForAsyncUtils.asyncFx(() -> {
            try {
                for (int i = 0; i < 60; i++) {
                    invoke(page, "addUserBubble", new Class<?>[]{String.class, boolean.class},
                            "filler line " + i, true);
                }
                invoke(page, "updateEmptyState", new Class<?>[0]);
                page.applyCss();
                page.layout();
                scrollPane.setVvalue(1.0);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static double vvalue(ScrollPane scrollPane) throws Exception {
        return WaitForAsyncUtils.asyncFx(scrollPane::getVvalue).get(10, TimeUnit.SECONDS);
    }

    @Test
    public void sendingRePinsToBottomIgnoringAutoScrollToggle() throws Exception {
        AIMainPage page = showPage();
        ScrollPane scrollPane = (ScrollPane) getField(page, "scrollPane");
        AiSettings aiSettings = (AiSettings) getField(page, "aiSettings");
        overflowAndPinBottom(page, scrollPane);

        // The user scrolled up to read history AND has streaming auto-follow turned OFF — neither
        // must stop a fresh send from jumping to the just-typed message (#1).
        WaitForAsyncUtils.asyncFx(() -> {
            aiSettings.autoScrollEnabledProperty().set(false);
            try {
                setField(page, "stickToBottom", false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            scrollPane.setVvalue(0.2);
        }).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(vvalue(scrollPane) < 0.5, "precondition: view is scrolled up, not at the bottom");

        WaitForAsyncUtils.asyncFx(() -> {
            try {
                invoke(page, "sendText", new Class<?>[]{String.class, String.class}, "hello there", null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(10, TimeUnit.SECONDS);
        // Two pumps for forceScrollToBottom's nested runLater.
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue((boolean) getField(page, "stickToBottom"),
                "a real user send must re-pin stickToBottom=true even after scrolling up (#1)");
        assertEquals(1.0, vvalue(scrollPane), 0.02,
                "a real user send must force-scroll to the bottom despite autoScroll being OFF (#1)");
    }

    @Test
    public void streamingAppendFollowsWhenPinned() throws Exception {
        AIMainPage page = showPage();
        ScrollPane scrollPane = (ScrollPane) getField(page, "scrollPane");
        AiSettings aiSettings = (AiSettings) getField(page, "aiSettings");
        overflowAndPinBottom(page, scrollPane);

        WaitForAsyncUtils.asyncFx(() -> {
            aiSettings.autoScrollEnabledProperty().set(true);
            try {
                setField(page, "stickToBottom", true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            scrollPane.setVvalue(0.3);
        }).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();

        appendBubbleThenScroll(page, scrollPane);

        assertEquals(1.0, vvalue(scrollPane), 0.02,
                "while pinned (stickToBottom + autoScroll), a streaming append follows to bottom (#2)");
    }

    @Test
    public void streamingAppendDoesNotYankWhenDetached() throws Exception {
        AIMainPage page = showPage();
        ScrollPane scrollPane = (ScrollPane) getField(page, "scrollPane");
        AiSettings aiSettings = (AiSettings) getField(page, "aiSettings");
        overflowAndPinBottom(page, scrollPane);

        // Detached: the user is reading history. autoScroll stays ON — the gate that must protect
        // them here is stickToBottom, not the toggle (#3).
        WaitForAsyncUtils.asyncFx(() -> {
            aiSettings.autoScrollEnabledProperty().set(true);
            try {
                setField(page, "stickToBottom", false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            scrollPane.setVvalue(0.3);
        }).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
        double before = vvalue(scrollPane);

        appendBubbleThenScroll(page, scrollPane);

        assertEquals(before, vvalue(scrollPane), 0.03,
                "while detached, a streaming append must NOT yank the view back to the bottom (#3)");
    }

    @Test
    public void wheelUpDetachesStickToBottom() throws Exception {
        AIMainPage page = showPage();
        ScrollPane scrollPane = (ScrollPane) getField(page, "scrollPane");
        overflowAndPinBottom(page, scrollPane);
        WaitForAsyncUtils.asyncFx(() -> {
            try {
                setField(page, "stickToBottom", true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(10, TimeUnit.SECONDS);

        // A wheel-up (deltaY > 0) means "I'm reading history" → detach.
        WaitForAsyncUtils.asyncFx(() -> scrollPane.fireEvent(wheel(scrollPane, 40)))
                .get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse((boolean) getField(page, "stickToBottom"),
                "a wheel-up gesture must detach stick-to-bottom so streaming stops following");
    }

    private static void appendBubbleThenScroll(AIMainPage page, ScrollPane scrollPane) throws Exception {
        WaitForAsyncUtils.asyncFx(() -> {
            try {
                // Simulate one streamed segment landing, then the code's own scrollToBottom() call.
                invoke(page, "addUserBubble", new Class<?>[]{String.class, boolean.class},
                        "streamed segment", true);
                page.applyCss();
                page.layout();
                invoke(page, "scrollToBottom", new Class<?>[0]);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static ScrollEvent wheel(javafx.scene.Node target, double deltaY) {
        return new ScrollEvent(ScrollEvent.SCROLL,
                0, 0, 0, 0, false, false, false, false, false, false,
                0, deltaY, 0, deltaY,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0,
                0, new PickResult(target, 0, 0));
    }
}
