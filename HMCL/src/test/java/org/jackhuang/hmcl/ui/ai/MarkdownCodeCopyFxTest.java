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
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.SVGContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxRobot;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// First TestFX-based UI test — the project's UI-test pipeline pilot (replacing the
/// retired ui-mirror / CLI harness lines). Drives a REAL JavaFX toolkit: renders an AI
/// chat code block and clicks its 复制 button with a robot, verifying the clipboard and
/// the button-label feedback end-to-end.
///
/// Runs headed on a developer machine; self-skips where no display is available
/// (headless CI), so the default `test` task stays green everywhere.
public final class MarkdownCodeCopyFxTest {

    private static final String CODE = "int a = 1;\nSystem.out.println(a);";

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
        // HiDPI Windows (e.g. 200% scaling): the Glass robot clicks at physical pixels while
        // node coordinates are logical, so every click lands off-target. Forcing the test
        // toolkit to run unscaled makes logical == physical and robot clicks reliable.
        // Must be set BEFORE the toolkit starts.
        System.setProperty("glass.win.uiScale", "100%");
        System.setProperty("prism.allowhidpi", "false");
        FxToolkit.registerPrimaryStage();
    }

    @AfterAll
    static void tearDownToolkit() throws Exception {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            FxToolkit.cleanupStages();
        }
    }

    @Test
    public void copyButtonPutsCodeOnClipboardAndFlipsLabel() throws Exception {
        FxToolkit.setupSceneRoot(() -> {
            MarkdownMessageView view = MarkdownMessageView.create("```java\n" + CODE + "\n```", 710);
            assertNotNull(view, "fenced code must be recognised as markdown");
            StackPane root = new StackPane(view);
            root.setPrefSize(600, 400);
            return root;
        });
        FxToolkit.showStage();
        WaitForAsyncUtils.waitForFxEvents();

        // Preserve whatever the developer had on the clipboard.
        String before = WaitForAsyncUtils.asyncFx(
                () -> Clipboard.getSystemClipboard().getString()).get(5, TimeUnit.SECONDS);
        try {
            // Event-level interaction, NOT a physical robot click: this machine is the user's
            // daily-driver desktop — a screen-coordinate robot steals the real mouse, races
            // against the user's own input, and mis-hits under Windows HiDPI. fire() exercises
            // the same onAction path through a real toolkit deterministically. Physical-click
            // coverage belongs to a headless Monocle setup (virtual robot), not the dev desktop.
            FxRobot robot = new FxRobot();
            // The copy control is now an icon toggle button (SVG.CONTENT_COPY) in the code header,
            // not the old text button that flipped its own 复制/已复制 label — so locate it under
            // .md-code-header and assert the glyph rather than the label text.
            JFXButton copyBtn = robot.lookup(".md-code-header .toggle-icon4").queryAs(JFXButton.class);
            assertTrue(copyBtn.getGraphic() instanceof SVGContainer,
                    "the copy control is an SVG icon button, got: " + copyBtn.getGraphic());
            assertEquals(SVG.CONTENT_COPY, ((SVGContainer) copyBtn.getGraphic()).getIcon(),
                    "before copying, the button shows the copy glyph");

            WaitForAsyncUtils.asyncFx(copyBtn::fire).get(5, TimeUnit.SECONDS);
            WaitForAsyncUtils.waitForFxEvents();

            String clipped = WaitForAsyncUtils.asyncFx(
                    () -> Clipboard.getSystemClipboard().getString()).get(5, TimeUnit.SECONDS);
            assertEquals(CODE, clipped, "clipboard must hold the raw code body");
            assertEquals(SVG.CHECK, ((SVGContainer) copyBtn.getGraphic()).getIcon(),
                    "the glyph flips to a check to acknowledge the copy");
        } finally {
            String restore = before;
            WaitForAsyncUtils.asyncFx(() -> {
                javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                cc.putString(restore != null ? restore : "");
                Clipboard.getSystemClipboard().setContent(cc);
            }).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void codeBlockScrollsHorizontallyInsteadOfWrapping() throws Exception {
        FxToolkit.setupSceneRoot(() -> {
            MarkdownMessageView view = MarkdownMessageView.create(
                    "```\nthis_is_a_very_long_single_line_of_code_that_should_scroll_and_never_char_wrap = 1\n```", 710);
            assertNotNull(view, "fenced code must be recognised as markdown");
            StackPane root = new StackPane(view);
            root.setPrefSize(600, 400);
            return root;
        });
        FxToolkit.showStage();
        WaitForAsyncUtils.waitForFxEvents();

        FxRobot robot = new FxRobot();
        ScrollPane scroll = robot.lookup(".md-code-scroll").queryAs(ScrollPane.class);
        assertNotNull(scroll, "code body must be wrapped in a horizontal ScrollPane");
        assertEquals(ScrollPane.ScrollBarPolicy.AS_NEEDED, scroll.getHbarPolicy(),
                "long lines must be reachable via a horizontal bar");
        assertEquals(ScrollPane.ScrollBarPolicy.NEVER, scroll.getVbarPolicy());
        assertFalse(scroll.isFitToWidth(),
                "fitToWidth must stay false so the flow keeps its natural width and does not char-wrap");
    }
}
