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
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.stage.Window;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
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

/// Structural gate for the composer redesign, updated for the v2 精修. Automated tests cannot judge
/// visual alignment/reflow — that is verified on a real machine — but they CAN pin the structural
/// invariants:
///  ① the Auto pill and the model selector both live INSIDE the composer card (they moved out of
///     the header);
///  ② v2 §7: Send/Stop is a square that lives in the INPUT ROW (`.ai-input-row`), NOT in the bottom
///     toolbar; the model selector + thinking pill + context ring sit in the toolbar's right group;
///  ③ v2 §6: the Auto pill sits in the WRAPPING left FlowPane while the thinking pill moved to the
///     right group (toolbar, not FlowPane);
///  ④ after shrinking the window to a very narrow width, Send stays visible and fully within the
///     composer card's horizontal bounds (it is pinned to the input row's right edge);
///  ⑤ v2 §5: the context ring exists in the toolbar and is clickable.
public final class ComposerLayoutFxTest {

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
    public void controlsLiveInComposerToolbarNotHeader() throws Exception {
        AIMainPage page = showPage();
        JFXButton sendBtn = (JFXButton) getField(page, "sendBtn");
        JFXButton thinkBtn = (JFXButton) getField(page, "thinkBtn");
        Label autoPill = (Label) getField(page, "approvalBadge");
        LineSelectButton<?> modelSelector = (LineSelectButton<?>) getField(page, "modelSelector");
        Node contextRing = (Node) getField(page, "contextRing");

        // ① Auto pill + model selector are inside the composer card (they moved down from the header).
        assertTrue(hasAncestorWithStyleClass(autoPill, "ai-composer"),
                "the Auto pill must live inside the composer card (moved out of the header)");
        assertTrue(hasAncestorWithStyleClass(modelSelector, "ai-composer"),
                "the model selector must live inside the composer card (moved out of the header)");

        // ② v2 §7: Send is a square in the INPUT ROW, not the bottom toolbar.
        assertTrue(hasAncestorWithStyleClass(sendBtn, "ai-input-row"),
                "the Send square must live in the input row (moved out of the bottom toolbar)");
        assertFalse(hasAncestorWithStyleClass(sendBtn, "ai-composer-toolbar"),
                "the Send square must NOT be in the bottom toolbar any more (v2 §7)");
        assertTrue(sendBtn.getStyleClass().contains("ai-send-square"),
                "the Send button must wear the compact square style class");

        // ③ v2 §6: model + thinking + ring are in the toolbar; the Auto pill sits in the wrapping
        //    left FlowPane while thinking moved to the right group (toolbar, NOT a FlowPane child).
        assertTrue(hasAncestorWithStyleClass(modelSelector, "ai-composer-toolbar"),
                "the model selector must live in the composer's bottom toolbar");
        assertTrue(hasAncestorWithStyleClass(thinkBtn, "ai-composer-toolbar"),
                "the thinking pill must live in the composer's bottom toolbar");
        assertFalse(hasAncestorOfType(thinkBtn, FlowPane.class),
                "v2 §6: the thinking pill moved OUT of the wrapping left group to the right group");
        assertTrue(hasAncestorOfType(autoPill, FlowPane.class),
                "the Auto pill must sit in the wrapping (FlowPane) left group");

        // ⑤ v2 §5: the context ring exists in the toolbar and is clickable.
        assertNotNull(contextRing, "the composer must expose a context ring node");
        assertTrue(contextRing.getStyleClass().contains("ai-context-ring"),
                "the context ring must wear the ai-context-ring style class");
        assertTrue(hasAncestorWithStyleClass(contextRing, "ai-composer-toolbar"),
                "the context ring must live in the composer's bottom toolbar");

        assertTrue(sendBtn.isVisible() && sendBtn.isManaged(), "Send must be visible/managed");
    }

    @Test
    public void sendSquareStaysWithinComposerWhenWindowIsNarrow() throws Exception {
        AIMainPage page = showPage();
        JFXButton sendBtn = (JFXButton) getField(page, "sendBtn");

        // Narrow the window aggressively. The Send square is pinned to the input row's right edge
        // and never enters an overflow — it must stay visible and within the composer card's bounds
        // at any width. Pixel-accurate reflow/alignment is a real-machine check per the spec.
        Window window = page.getScene().getWindow();
        WaitForAsyncUtils.asyncFx(() -> window.setWidth(560)).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();

        Node composer = page.lookup(".ai-composer");
        assertNotNull(composer, "composer must expose a card node");

        assertTrue(sendBtn.isVisible() && sendBtn.isManaged(),
                "Send must remain visible/managed at a narrow width (never folded into an overflow)");
        Bounds send = sendBtn.localToScene(sendBtn.getBoundsInLocal());
        Bounds card = composer.localToScene(composer.getBoundsInLocal());
        assertTrue(send.getWidth() > 0 && send.getHeight() > 0, "Send must have real bounds");
        // Send stays inside the composer card's right edge (allow a 1px rounding slack).
        assertTrue(send.getMaxX() <= card.getMaxX() + 1.0,
                "Send must stay within the composer's right edge (send.maxX=" + send.getMaxX()
                        + ", card.maxX=" + card.getMaxX() + ")");
    }

    @Test
    public void thinkingPillOpensSliderPopupThatDrivesEffort() throws Exception {
        AIMainPage page = showPage();
        JFXButton thinkBtn = (JFXButton) getField(page, "thinkBtn");

        // v2 §3: clicking the thinking pill opens a gradient SLIDER (no checkmark menu). The slider
        // is exposed as a field; driving it must persist the reasoning effort.
        WaitForAsyncUtils.asyncFx(thinkBtn::fire).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();

        javafx.scene.control.Slider slider =
                (javafx.scene.control.Slider) getField(page, "thinkingSlider");
        assertNotNull(slider, "opening the thinking pill must build the effort slider");
        assertEquals(0.0, slider.getMin(), "slider domain starts at 0 (none)");
        assertEquals(5.0, slider.getMax(), "slider domain ends at 5 (max/highest)");

        // Move the handle to index 3 (= "high") and confirm it persisted to reasoning effort.
        WaitForAsyncUtils.asyncFx(() -> slider.setValue(3)).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();

        Object aiSettings = getField(page, "aiSettings");
        String effort = (String) invoke(aiSettings, "getReasoningEffort", new Class<?>[0]);
        assertEquals("high", effort, "dragging the slider to index 3 must persist effort=high");
    }

    private static boolean hasAncestorWithStyleClass(Node node, String styleClass) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n.getStyleClass().contains(styleClass)) return true;
        }
        return false;
    }

    private static boolean hasAncestorOfType(Node node, Class<?> type) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (type.isInstance(n)) return true;
        }
        return false;
    }
}
