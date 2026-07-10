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

/// Structural gate for the two-row composer redesign (mode/thinking pills + attach/slash on the
/// left, model selector + Send on the right of a bottom toolbar). Automated tests cannot judge
/// visual alignment/reflow — that is verified on a real machine — but they CAN pin the invariants
/// that make the "Send never overflows" guarantee true by construction:
///  ① the Auto pill and the model selector both live INSIDE the composer card (they moved out of
///     the header);
///  ② Send + model sit in the toolbar; the left pill group is a FlowPane (i.e. it can WRAP rather
///     than push Send out);
///  ③ after shrinking the window to a very narrow width, Send stays visible and fully within the
///     scene's horizontal bounds.
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

        // ① Auto pill + model selector are inside the composer card (they moved down from the header).
        assertTrue(hasAncestorWithStyleClass(autoPill, "ai-composer"),
                "the Auto pill must live inside the composer card (moved out of the header)");
        assertTrue(hasAncestorWithStyleClass(modelSelector, "ai-composer"),
                "the model selector must live inside the composer card (moved out of the header)");

        // ② Send + model are in the bottom toolbar; the pills sit in a WRAPPING FlowPane.
        assertTrue(hasAncestorWithStyleClass(sendBtn, "ai-composer-toolbar"),
                "the Send button must live in the composer's bottom toolbar");
        assertTrue(hasAncestorWithStyleClass(modelSelector, "ai-composer-toolbar"),
                "the model selector must live in the composer's bottom toolbar");
        assertTrue(hasAncestorOfType(thinkBtn, FlowPane.class),
                "the thinking pill must sit in the wrapping (FlowPane) left group");
        assertTrue(hasAncestorOfType(autoPill, FlowPane.class),
                "the Auto pill must sit in the wrapping (FlowPane) left group");

        assertTrue(sendBtn.isVisible() && sendBtn.isManaged(), "Send must be visible/managed");
    }

    @Test
    public void sendButtonStaysWithinToolbarWhenWindowIsNarrow() throws Exception {
        AIMainPage page = showPage();
        JFXButton sendBtn = (JFXButton) getField(page, "sendBtn");

        // Narrow the window aggressively. The overall page has a sidebar so the whole thing can't
        // shrink to nothing, but the construction invariant we assert holds at ANY width: the
        // right-pinned Send never escapes its bottom toolbar to the right (the left pill group is a
        // FlowPane that wraps instead of shoving Send out). Pixel-accurate reflow/alignment is a
        // real-machine check per the spec.
        Window window = page.getScene().getWindow();
        WaitForAsyncUtils.asyncFx(() -> window.setWidth(560)).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();

        Node toolbar = page.lookup(".ai-composer-toolbar");
        assertNotNull(toolbar, "composer must expose a bottom toolbar node");

        assertTrue(sendBtn.isVisible() && sendBtn.isManaged(),
                "Send must remain visible/managed at a narrow width (never folded into an overflow)");
        Bounds send = sendBtn.localToScene(sendBtn.getBoundsInLocal());
        Bounds bar = toolbar.localToScene(toolbar.getBoundsInLocal());
        assertTrue(send.getWidth() > 0 && send.getHeight() > 0, "Send must have real bounds");
        // Send stays inside its toolbar's right edge (allow a 1px rounding slack) — it is pinned
        // right and the wrapping left group yields space before Send ever would.
        assertTrue(send.getMaxX() <= bar.getMaxX() + 1.0,
                "Send must stay within the toolbar's right edge (send.maxX=" + send.getMaxX()
                        + ", toolbar.maxX=" + bar.getMaxX() + ")");
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
