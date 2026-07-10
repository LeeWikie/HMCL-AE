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

import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.IconedMenuItem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// B3 item 0 regression guard: the thinking-level popup rendered its {@link IconedMenuItem} rows
/// as white-on-white — the {@code #label} resolved to a transparent/near-background fill because
/// nothing pinned it and the popup inherited an unfriendly text-fill context from the composer.
/// root.css now pins {@code .iconed-menu-item #label} (and {@code .iconed-item #label}) to
/// {@code -monet-on-surface}. This test applies the REAL root.css against a known on-surface
/// looked-up colour and asserts the label's resolved textFill is that colour — opaque and clearly
/// distinct from the surface behind it. Direct CSS application, no robot (A7).
public final class IconedMenuItemFxTest {

    private static final Color ON_SURFACE = Color.web("#1b1b1b");
    private static final Color SURFACE = Color.web("#fdfdfd");

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
        FxToolkit.registerPrimaryStage();
    }

    @AfterAll
    static void tearDownToolkit() throws Exception {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            FxToolkit.cleanupStages();
        }
    }

    @Test
    public void menuItemLabelResolvesToVisibleOnSurfaceFill() throws Exception {
        Label[] labelRef = new Label[1];
        FxToolkit.setupSceneRoot(() -> {
            IconedMenuItem item = new IconedMenuItem(SVG.CHECK, "平衡", () -> { }, null);
            StackPane root = new StackPane(item);
            root.setPrefSize(240, 60);
            // Define the looked-up colours the pinned rule (and the rippler) resolve against, so
            // the real stylesheet can apply exactly as it does under a theme.
            root.setStyle("-monet-on-surface: #1b1b1b; -monet-surface: #fdfdfd;"
                    + " -monet-secondary-container: #d7e3ff;");
            root.getStylesheets().add(
                    IconedMenuItem.class.getResource("/assets/css/root.css").toExternalForm());
            labelRef[0] = item.getLabel();
            return root;
        });
        FxToolkit.showStage();
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.asyncFx(() -> labelRef[0].applyCss()).get(5, java.util.concurrent.TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();

        Paint fill = labelRef[0].getTextFill();
        assertInstanceOf(Color.class, fill, "text fill must resolve to a concrete colour");
        Color color = (Color) fill;
        assertTrue(color.getOpacity() > 0.99,
                "the menu-item label must not be transparent (the invisible-text regression), got opacity "
                        + color.getOpacity());
        assertEquals(ON_SURFACE, color, "the label must resolve to -monet-on-surface");
        assertNotEquals(SURFACE, color, "the label colour must contrast with the surface behind it");
    }
}
