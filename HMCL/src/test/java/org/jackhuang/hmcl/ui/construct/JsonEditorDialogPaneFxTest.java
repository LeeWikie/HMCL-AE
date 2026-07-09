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
package org.jackhuang.hmcl.ui.construct;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Renders a real {@link JsonEditorDialogPane} (RichTextFX {@code CodeArea} + line numbers +
/// syntax highlighting) against an actual JavaFX toolkit — a first real-runtime check that the
/// RichTextFX integration this class introduces to the codebase doesn't throw or misbehave, since
/// compiling cleanly does not exercise `CodeArea`'s skin, the `LineNumberFactory` paragraph
/// graphics, or the reactive `multiPlainChanges()` subscription at all.
///
/// Self-skips on a headless CI box, matching this package's other {@code *FxTest} tests.
public final class JsonEditorDialogPaneFxTest {

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
        System.setProperty("glass.win.uiScale", "100%");
        System.setProperty("prism.allowhidpi", "false");
        FxToolkit.registerPrimaryStage();
        ensureSettingsManagerLoaded();
    }

    @AfterAll
    static void tearDownToolkit() throws Exception {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            FxToolkit.cleanupStages();
        }
    }

    @BeforeEach
    void discardExceptionsLeakedByUnrelatedEarlierTests() {
        // TestFX queues any exception thrown on the FX Application Thread and re-throws it,
        // wrapped, from the NEXT waitForFxEvents() call anywhere — including in a completely
        // unrelated later test class. Other AI-tool tests in this module install a throwaway
        // Profiles/SettingsManager via a fixture and revert it on close(), but a
        // Platform.runLater(...) callback registered before that revert can still fire afterward;
        // when it does, it reads the now-reverted (unloaded) SettingsManager and throws. Clear
        // whatever accumulated before this test's own window so only OUR exceptions can fail it.
        WaitForAsyncUtils.clearExceptions();
    }

    /// {@code DialogPane}'s {@code SpinnerPane} skin builds a {@code TransitionPane}, which reads
    /// {@code SettingsManager.settings()} the first time any animation-aware node is shown —
    /// normally loaded during the app's real startup sequence, which this bare TestFX harness never
    /// runs. Seed just enough (a throwaway {@link LauncherSettings}) via reflection so that lazy
    /// read doesn't throw {@code IllegalStateException("Configuration hasn't been loaded")},
    /// mirroring {@code org.jackhuang.hmcl.ui.ai.tools.ProfileFixture}'s technique for the same
    /// field (that fixture lives in a different package and restores the field afterward, which
    /// this test doesn't need to bother with — nothing here reads real settings back).
    private static void ensureSettingsManagerLoaded() throws ReflectiveOperationException {
        Field field = SettingsManager.class.getDeclaredField("launcherSettings");
        field.setAccessible(true);
        if (field.get(null) == null) {
            field.set(null, new LauncherSettings());
        }
    }

    @Test
    void seedsTitleAndInitialTextAndStartsValidWhenTextIsAcceptable() throws Exception {
        AtomicReference<JsonEditorDialogPane> paneRef = new AtomicReference<>();
        FxToolkit.setupSceneRoot(() -> {
            JsonEditorDialogPane pane = new JsonEditorDialogPane(
                    "编辑测试", "{\"a\": 1}", text -> null, (text, handler) -> handler.resolve());
            paneRef.set(pane);
            StackPane root = new StackPane(pane);
            root.setPrefSize(800, 600);
            return root;
        });
        FxToolkit.showStage();
        WaitForAsyncUtils.waitForFxEvents();

        JsonEditorDialogPane pane = paneRef.get();
        assertEquals("编辑测试", pane.getTitle());
        assertEquals("{\"a\": 1}", pane.codeArea().getText());
        assertTrue(pane.isValid(), "an always-valid validator must leave the dialog valid from the start");
    }

    @Test
    void invalidInitialTextDisablesAcceptAndShowsTheValidatorsMessage() throws Exception {
        AtomicReference<JsonEditorDialogPane> paneRef = new AtomicReference<>();
        FxToolkit.setupSceneRoot(() -> {
            JsonEditorDialogPane pane = new JsonEditorDialogPane(
                    "编辑测试", "not json at all", text -> "总是无效", (text, handler) -> handler.resolve());
            paneRef.set(pane);
            StackPane root = new StackPane(pane);
            root.setPrefSize(800, 600);
            return root;
        });
        FxToolkit.showStage();
        WaitForAsyncUtils.waitForFxEvents();

        JsonEditorDialogPane pane = paneRef.get();
        assertFalse(pane.isValid());
        assertEquals("总是无效", pane.warningLabel.getText());
    }

    @Test
    void liveEditRevalidatesAfterTheDebounceWindow() throws Exception {
        AtomicReference<JsonEditorDialogPane> paneRef = new AtomicReference<>();
        FxToolkit.setupSceneRoot(() -> {
            // Only the literal text "valid" passes — anything else (including the seeded text) is
            // rejected, so the dialog must start invalid and only flip once the live edit below
            // lands and the debounced re-validation actually runs.
            JsonEditorDialogPane pane = new JsonEditorDialogPane(
                    "编辑测试", "invalid-to-start", text -> "valid".equals(text) ? null : "不是 valid",
                    (text, handler) -> handler.resolve());
            paneRef.set(pane);
            StackPane root = new StackPane(pane);
            root.setPrefSize(800, 600);
            return root;
        });
        FxToolkit.showStage();
        WaitForAsyncUtils.waitForFxEvents();

        JsonEditorDialogPane pane = paneRef.get();
        assertFalse(pane.isValid(), "must start invalid per the validator seeded above");

        javafx.application.Platform.runLater(() -> {
            org.fxmisc.richtext.CodeArea area = pane.codeArea();
            area.selectAll();
            area.replaceSelection("valid");
        });
        WaitForAsyncUtils.waitForFxEvents();
        // The dialog debounces re-validation by 80ms (JsonEditorDialogPane's successionEnds
        // window) after the last keystroke, so give it a bit of real time to fire.
        long deadline = System.currentTimeMillis() + 2000;
        while (!pane.isValid() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            WaitForAsyncUtils.waitForFxEvents();
        }

        assertTrue(pane.isValid(), "replacing the text with the one string the validator accepts "
                + "must flip the dialog valid once the debounced re-check runs");
        assertEquals("", pane.warningLabel.getText());
    }
}
