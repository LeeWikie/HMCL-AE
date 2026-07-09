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

import javafx.event.Event;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// FX component test for the shared [CollapseHeader] (blueprint task A5 / CP §P3):
/// `toggle()` flips the expanded property, the single KEYBOARD_ARROW_DOWN chevron rotates
/// between -90 (collapsed, points right) and 0 (expanded, points down), and the whole row
/// is a click hot zone (event injection, no physical robot — A7 discipline).
///
/// The chevron assertions are written to hold in BOTH `AnimationUtils` branches: this
/// class seeds settings with animations disabled (so the direct `setRotate` branch runs
/// and the value is exact immediately), but if an earlier test in the same JVM already
/// initialized `AnimationUtils` with animations enabled, the 150ms Timeline still settles
/// on the same terminal angle — so we poll to the terminal value before asserting.
public final class CollapseHeaderFxTest {

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
        // Seed BEFORE any animation-aware code runs: AnimationUtils' static init reads
        // SettingsManager.settings() (throws "Configuration hasn't been loaded" in a bare
        // TestFX harness). Same reflection technique as JsonEditorDialogPaneFxTest.
        ensureSettingsManagerLoaded();
        FxToolkit.registerPrimaryStage();
    }

    @AfterAll
    static void tearDownToolkit() throws Exception {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            FxToolkit.cleanupStages();
        }
    }

    @BeforeEach
    void discardExceptionsLeakedByUnrelatedEarlierTests() {
        // See JsonEditorDialogPaneFxTest: TestFX re-throws FX-thread exceptions queued by
        // earlier, unrelated test classes from our waitForFxEvents() calls. Clear them.
        WaitForAsyncUtils.clearExceptions();
    }

    private static void ensureSettingsManagerLoaded() throws ReflectiveOperationException {
        Field field = SettingsManager.class.getDeclaredField("launcherSettings");
        field.setAccessible(true);
        if (field.get(null) == null) {
            LauncherSettings settings = new LauncherSettings();
            // Prefer the deterministic no-animation branch (blueprint A5 test spec asks for
            // exact 0/-90 rotate values on that branch). Only effective if AnimationUtils
            // hasn't been class-initialized yet; the polling below covers the other case.
            settings.animationDisabledProperty().set(true);
            field.set(null, settings);
        }
    }

    private static CollapseHeader showHeader() throws Exception {
        CollapseHeader[] ref = new CollapseHeader[1];
        FxToolkit.setupSceneRoot(() -> {
            ref[0] = new CollapseHeader("思考过程");
            StackPane root = new StackPane(ref[0]);
            root.setPrefSize(500, 200);
            return root;
        });
        FxToolkit.showStage();
        WaitForAsyncUtils.waitForFxEvents();
        return ref[0];
    }

    /// Polls until the chevron settles on the target angle (immediate when animations are
    /// disabled; <=150ms Timeline otherwise), then asserts the exact terminal value.
    private static void assertChevronSettlesAt(CollapseHeader header, double target) throws Exception {
        WaitForAsyncUtils.waitFor(2, TimeUnit.SECONDS,
                () -> Math.abs(header.getChevron().getRotate() - target) < 0.5);
        assertEquals(target, header.getChevron().getRotate(), 0.5,
                "chevron must settle exactly at " + target + " (collapsed=-90 points right, expanded=0 points down)");
    }

    @Test
    public void toggleFlipsExpandedAndRotatesChevron() throws Exception {
        CollapseHeader header = showHeader();

        // Initial state: collapsed, chevron points right (-90), title text as given.
        assertFalse(header.isExpanded(), "starts collapsed");
        assertEquals("思考过程", header.getTitleLabel().getText());
        assertEquals(-90, header.getChevron().getRotate(), 0.5, "collapsed chevron points right (-90)");
        assertTrue(header.getStyleClass().contains("ai-collapse-header"));
        assertTrue(header.getTitleLabel().getStyleClass().contains("ai-tool-card-header"));

        // toggle() → expanded flips to true, chevron settles pointing down (0).
        WaitForAsyncUtils.asyncFx(header::toggle).get(5, TimeUnit.SECONDS);
        assertTrue(header.isExpanded(), "toggle() expands");
        assertChevronSettlesAt(header, 0);

        // toggle() again → back to collapsed, chevron settles pointing right (-90).
        WaitForAsyncUtils.asyncFx(header::toggle).get(5, TimeUnit.SECONDS);
        assertFalse(header.isExpanded(), "second toggle() collapses");
        assertChevronSettlesAt(header, -90);

        // Rapid double-toggle mid-animation must stay idempotent (stop-before-restart):
        // net effect of two toggles is the original state and the original angle.
        WaitForAsyncUtils.asyncFx(() -> {
            header.toggle();
            header.toggle();
        }).get(5, TimeUnit.SECONDS);
        assertFalse(header.isExpanded(), "two rapid toggles cancel out");
        assertChevronSettlesAt(header, -90);
    }

    @Test
    public void wholeRowClickTogglesAndProgrammaticSetterRotates() throws Exception {
        CollapseHeader header = showHeader();

        // Whole-row hot zone: a primary-button MOUSE_CLICKED anywhere on the header row
        // toggles (FXUtils.onClicked wiring) — injected via Event.fireEvent, no robot.
        WaitForAsyncUtils.asyncFx(() -> Event.fireEvent(header, new MouseEvent(
                MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null)
        )).get(5, TimeUnit.SECONDS);
        assertTrue(header.isExpanded(), "primary click on the row expands");
        assertChevronSettlesAt(header, 0);

        // setExpanded(...) works as the programmatic path (streaming collapse in B4 consumers).
        WaitForAsyncUtils.asyncFx(() -> header.setExpanded(false)).get(5, TimeUnit.SECONDS);
        assertFalse(header.isExpanded());
        assertChevronSettlesAt(header, -90);

        // setExpanded to the same value is a no-op (property semantics): no angle change.
        WaitForAsyncUtils.asyncFx(() -> header.setExpanded(false)).get(5, TimeUnit.SECONDS);
        assertFalse(header.isExpanded());
        assertEquals(-90, header.getChevron().getRotate(), 0.5, "idempotent setExpanded keeps the angle");
    }
}
