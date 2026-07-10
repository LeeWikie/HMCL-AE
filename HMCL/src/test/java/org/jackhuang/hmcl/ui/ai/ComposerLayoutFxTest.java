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

/// Structural gate for the composer redesign, updated for the v2 精修 and then for the v3 compact-
/// pill height fix. Automated tests cannot judge visual alignment/reflow — that is verified on a
/// real machine — but they CAN pin the structural invariants AND (unlike alignment) the exact
/// pixel heights JavaFX itself computed, by driving a REAL {@link AIMainPage} through TestFX and
/// reading {@code Node.getHeight()} / {@code getLayoutBounds()} after layout ({@code
/// getBoundsInLocal()} is deliberately avoided for height assertions — it also unions in a raised
/// button's drop-shadow effect, overstating the real footprint):
///  ① the Auto pill and the model selector button both live INSIDE the composer card (they moved
///     out of the header);
///  ② v2 §7: Send/Stop is a square that lives in the INPUT ROW (`.ai-input-row`), NOT in the bottom
///     toolbar; the model selector + thinking pill + context ring sit in the toolbar's right group;
///  ③ v2 §6: the Auto pill sits in the WRAPPING left FlowPane while the thinking pill moved to the
///     right group (toolbar, not FlowPane);
///  ④ after shrinking the window to a very narrow width, Send stays visible and fully within the
///     composer card's horizontal bounds (it is pinned to the input row's right edge);
///  ⑤ v2 §5: the context ring exists in the toolbar and is clickable;
///  ⑥ v3: the model selector is rendered by a COMPACT `modelSelectorBtn` pill (~25px, matching the
///     Auto/thinking/context-ring siblings), never by the headless `modelSelector` LineSelectButton
///     — whose LineComponent ancestor hardcodes a 48px min-height floor in Java that no CSS override
///     can defeat (confirmed root cause of the "composer empty state is way too tall" report: with
///     the real LineSelectButton in the toolbar, it alone forced the whole toolbar row to ~55px and
///     the whole composer card to ~133px in the empty state) — {@link #modelSelectorIsCompactPill()}
///     pins the fixed height, and {@link #composerEmptyStateIsCompact()} pins the resulting overall
///     empty-state dimensions.
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
        // v3: the VISIBLE model control is the compact modelSelectorBtn pill, not the headless
        // modelSelector LineSelectButton (see class doc ⑥ / modelSelectorIsCompactPill() below).
        JFXButton modelSelectorBtn = (JFXButton) getField(page, "modelSelectorBtn");
        Node contextRing = (Node) getField(page, "contextRing");

        // ① Auto pill + model selector button are inside the composer card (moved down from the header).
        assertTrue(hasAncestorWithStyleClass(autoPill, "ai-composer"),
                "the Auto pill must live inside the composer card (moved out of the header)");
        assertTrue(hasAncestorWithStyleClass(modelSelectorBtn, "ai-composer"),
                "the model selector button must live inside the composer card (moved out of the header)");

        // ② v2 §7: Send is a square in the INPUT ROW, not the bottom toolbar.
        assertTrue(hasAncestorWithStyleClass(sendBtn, "ai-input-row"),
                "the Send square must live in the input row (moved out of the bottom toolbar)");
        assertFalse(hasAncestorWithStyleClass(sendBtn, "ai-composer-toolbar"),
                "the Send square must NOT be in the bottom toolbar any more (v2 §7)");
        assertTrue(sendBtn.getStyleClass().contains("ai-send-square"),
                "the Send button must wear the compact square style class");

        // ③ v2 §6: model + thinking + ring are in the toolbar; the Auto pill sits in the wrapping
        //    left FlowPane while thinking moved to the right group (toolbar, NOT a FlowPane child).
        assertTrue(hasAncestorWithStyleClass(modelSelectorBtn, "ai-composer-toolbar"),
                "the model selector button must live in the composer's bottom toolbar");
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
    public void modelSelectorIsCompactPill() throws Exception {
        AIMainPage page = showPage();

        // The old, rejected shape: modelSelector is a LineSelectButton, and LineComponent (its
        // grandparent) hardcodes `private static final double MIN_HEIGHT = 48.0` in BOTH its
        // constructor (an unconditional setMinHeight(48)) and in computeMinHeight()/
        // computePrefHeight() (Math.max(MIN_HEIGHT, ...), re-applied on every width change) — no
        // CSS override can beat that floor. Confirming that object is never attached to the scene
        // pins the fix: the toolbar must never be able to regrow the old 48px/55px/133px chain.
        LineSelectButton<?> modelSelector = (LineSelectButton<?>) getField(page, "modelSelector");
        assertNull(modelSelector.getParent(),
                "the headless modelSelector LineSelectButton must never be added to the scene graph "
                        + "(its LineComponent ancestor hardcodes a 48px min-height floor in Java that "
                        + "would balloon the whole toolbar row again)");

        JFXButton modelSelectorBtn = (JFXButton) getField(page, "modelSelectorBtn");
        JFXButton thinkBtn = (JFXButton) getField(page, "thinkBtn");
        Node contextRing = (Node) getField(page, "contextRing");
        assertNotNull(modelSelectorBtn.getParent(), "modelSelectorBtn must be in the scene graph");

        double modelH = modelSelectorBtn.getHeight();
        double thinkH = thinkBtn.getHeight();
        double ringH = contextRing.getLayoutBounds().getHeight();

        // Precise: modelSelectorBtn wears the exact same .ai-toolbar-pill style as thinkBtn, so the
        // two MUST resolve to the identical real layout height.
        assertEquals(thinkH, modelH, 0.5,
                "the model selector pill must be the SAME height as the thinking pill "
                        + "(both wear .ai-toolbar-pill) — model=" + modelH + " think=" + thinkH);
        assertEquals(25.0, modelH, 1.0,
                "the model selector pill must be the compact ~25px .ai-toolbar-pill height, "
                        + "not LineComponent's 48px floor — was " + modelH);
        assertEquals(ringH, modelH, 2.0,
                "the model selector pill must be within a couple px of the context ring's height "
                        + "(both are toolbar siblings meant to read as one even strip) — model="
                        + modelH + " ring=" + ringH);
    }

    @Test
    public void composerEmptyStateIsCompact() throws Exception {
        AIMainPage page = showPage();

        javafx.scene.control.TextArea inputField =
                (javafx.scene.control.TextArea) getField(page, "inputField");
        Node inputRow = page.lookup(".ai-input-row");
        Node toolbar = page.lookup(".ai-composer-toolbar");
        Node composerCard = page.lookup(".ai-composer");
        assertNotNull(inputRow, "composer must expose its input row node");
        assertNotNull(toolbar, "composer must expose its toolbar node");
        assertNotNull(composerCard, "composer must expose its card node");

        // NOTE: layout height, not getBoundsInLocal() — the latter also unions in the Send square's
        // raised-button drop-shadow effect (a pre-existing cosmetic bleed a couple px below its own
        // box, unrelated to this fix), which is not space the layout actually reserves and would
        // make these numbers overstate the real footprint.

        // ① empty-state input field rests at ~1 line (~26px), not the old, larger 34px.
        assertEquals(26.0, inputField.getMinHeight(), 1.0,
                "empty-state input field min-height must be ~26px (1 line) — was "
                        + inputField.getMinHeight());
        assertEquals(inputField.getMinHeight(), inputField.getPrefHeight(), 0.01,
                "empty-state input field must rest exactly at its min-height floor (no content to grow for)");
        double inputRowH = inputRow.getLayoutBounds().getHeight();
        assertEquals(26.0, inputRowH, 2.0,
                "input row's real layout height must be ~26px in the empty state — was " + inputRowH);

        // ② the toolbar row is a slim strip now that no child forces a 48px floor — measured exactly
        // 25px on this machine (every child is the same ~25px .ai-toolbar-pill height); loosely
        // bounded so minor font/DPI drift elsewhere doesn't make this test flaky, but tight enough
        // to fail hard if the old 48px floor (which alone pushed this to ~55px) ever comes back.
        double toolbarH = toolbar.getLayoutBounds().getHeight();
        assertTrue(toolbarH > 15 && toolbarH < 35,
                "toolbar row must be a compact strip (~25px), not ballooned by the old 48px "
                        + "LineComponent floor (~55px+) — was " + toolbarH);

        // ③ the whole composer card in the empty state is compact overall — measured 59px on this
        // machine, nowhere near the pre-fix ~133px (modelSelector's 48px floor alone pushed the
        // toolbar to ~55px and the whole card past ~130px; see modelSelectorIsCompactPill()) and
        // comfortably under the ~84px target given in the bug report.
        double cardH = composerCard.getLayoutBounds().getHeight();
        assertTrue(cardH > 40 && cardH < 90,
                "composer card must be compact overall in the empty state (~59-84px), not the "
                        + "pre-fix ~133px — was " + cardH);
    }

    @Test
    public void modelSelectorPillOpensAndTogglesPopup() throws Exception {
        AIMainPage page = showPage();
        JFXButton modelSelectorBtn = (JFXButton) getField(page, "modelSelectorBtn");

        // Clicking the compact pill must still open the model-picker popup (mirrors
        // showApprovalModePopup's toggle behaviour: a second click while showing closes it instead
        // of stacking another) — the interaction the old LineSelectButton used to provide natively.
        WaitForAsyncUtils.asyncFx(modelSelectorBtn::fire).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();

        com.jfoenix.controls.JFXPopup popup =
                (com.jfoenix.controls.JFXPopup) getField(page, "modelSelectorPopup");
        assertNotNull(popup, "clicking the model selector pill must build/open its popup");
        assertTrue(popup.isShowing(), "the model selector popup must be showing after one click");

        WaitForAsyncUtils.asyncFx(modelSelectorBtn::fire).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(popup.isShowing(), "a second click must close the popup instead of stacking another");
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
