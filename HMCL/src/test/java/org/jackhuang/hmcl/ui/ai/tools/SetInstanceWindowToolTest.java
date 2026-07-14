/*
 * Hello Minecraft! Launcher - Agent Experience
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
package org.jackhuang.hmcl.ui.ai.tools;

import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.setting.GameWindowType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [SetInstanceWindowTool]'s metadata/description contract, its parameter-validation guards
/// (illegal `windowType`, an out-of-range dimension, width/height paired with a non-windowed mode),
/// and the report-only / write paths, all through the real [ProfileFixture]-backed instance.
///
/// Unlike [RollbackModToolTest]'s "missing 'mod'" guard, none of this tool's guards can be exercised
/// WITHOUT a fixture: `SetInstanceWindowTool.execute` resolves `Profiles.getSelectedProfile()` and
/// then the target instance BEFORE it ever looks at `windowType`/`width`/`height` (see the source),
/// so even the pure parameter-shape guards below run against a real, fixture-backed instance —
/// exactly the same reasoning [DeleteInstanceToolTest] and [EditInstanceToolTest] already apply for
/// their own guard tests.
///
/// The actual mutation ("write windowType/width/height + register overrideProperties") runs on the
/// JavaFX Application Thread ([javafx.application.Platform#runLater]), so that case is gated behind
/// `JavaFXLauncher#isStarted` exactly like [SetInstanceJvmArgsToolTest] — it simply doesn't run in a
/// headless environment where the JavaFX toolkit failed to start, rather than failing the build.
public final class SetInstanceWindowToolTest {

    private final SetInstanceWindowTool tool = new SetInstanceWindowTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("set_instance_window", tool.getName());
        String description = tool.getDescription();
        assertTrue(description.contains("windowType"), "must document the 'windowType' parameter: " + description);
        assertTrue(description.contains("width and height"),
                "must document the 'width'/'height' parameters: " + description);
        assertTrue(description.contains("WRITES"),
                "must disclose the write permission level: " + description);
        assertTrue(description.contains("REPORTS"),
                "must document the report-only (all-omitted) fallback: " + description);
        assertTrue(description.contains("rejected"),
                "must document that width/height paired with a non-windowed mode is rejected: " + description);
    }

    @Test
    void nonexistentInstanceFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");

            ToolResult result = tool.execute(Map.of("instance", "DoesNotExist", "windowType", "windowed"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("does not exist"), "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("Existing"),
                    "the failure should list the real instance names (candidate list): " + result.getError());
        }
    }

    @Test
    void illegalWindowTypeIsRejected() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Vanilla");

            ToolResult result = tool.execute(Map.of("instance", "Vanilla", "windowType", "borderless"));

            assertFalse(result.isSuccess(), "an unrecognized windowType must be rejected");
            assertTrue(result.getError().contains("windowed, fullscreen, maximized"),
                    "the failure must enumerate the accepted values: " + result.getError());
            assertTrue(result.getError().contains("borderless"),
                    "the failure must echo back the offending value: " + result.getError());
        }
    }

    @Test
    void widthPairedWithNonWindowedModeIsRejected() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Fullscreener");

            ToolResult result = tool.execute(Map.of("instance", "Fullscreener",
                    "windowType", "fullscreen", "width", 1024));

            assertFalse(result.isSuccess(), "width supplied with a non-windowed mode must be rejected");
            assertTrue(result.getError().contains("only take effect in windowed mode"),
                    "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("fullscreen"),
                    "the failure should name the offending target mode: " + result.getError());
        }
    }

    @Test
    void widthAboveMaxDimensionIsRejected() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("TooWide");

            ToolResult result = tool.execute(Map.of("instance", "TooWide",
                    "windowType", "windowed", "width", 20000));

            assertFalse(result.isSuccess(), "a width past the sanity ceiling must be rejected");
            assertTrue(result.getError().contains("unreasonably large"), "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("16384"),
                    "the failure should name the ceiling: " + result.getError());
        }
    }

    @Test
    void nonPositiveHeightIsRejected() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("ZeroHeight");

            ToolResult result = tool.execute(Map.of("instance", "ZeroHeight",
                    "windowType", "windowed", "height", 0));

            assertFalse(result.isSuccess(), "a non-positive height must be rejected");
            assertTrue(result.getError().contains("must be a positive integer"),
                    "unexpected message: " + result.getError());
        }
    }

    @Test
    void allParametersOmittedReportsCurrentStateWithoutMutating() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Reportable");

            ToolResult result = tool.execute(Map.of("instance", "Reportable"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("windowed"),
                    "a fresh instance's default window mode is windowed: " + result.getOutput());
            assertTrue(result.getOutput().contains("inherited from the parent preset"),
                    "an un-overridden setting must be reported as inherited: " + result.getOutput());
            assertTrue(result.getOutput().contains("854x480"),
                    "must report the actual default windowed size: " + result.getOutput());
            assertTrue(result.getOutput().contains("To change it"),
                    "a report-only call must point back at how to actually change it: " + result.getOutput());

            // Nothing must have been written: no per-instance override exists yet.
            GameSettings.Instance setting = fx.repository().getInstanceGameSettings("Reportable");
            assertTrue(setting == null || setting.getOverrideProperties().isEmpty(),
                    "a report-only call must not create/mutate the instance's window override");
        }
    }

    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    void setsWindowedModeAndSizeAndRegistersBothOverrides() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Windowed");

            ToolResult result = tool.execute(Map.of("instance", "Windowed",
                    "windowType", "windowed", "width", 1280, "height", 720));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("windowed"), "unexpected message: " + result.getOutput());
            assertTrue(result.getOutput().contains("1280x720"), "unexpected message: " + result.getOutput());

            // ProfileFixture's Profiles.init() kicks off one background refreshVersionsAsync() the
            // instant the fixture selects its profile; that unrelated, one-shot async scan can still
            // be in flight and briefly reload HMCLGameRepository's in-memory settings map from disk
            // right after our own mutation. Poll for eventual consistency instead of asserting
            // immediately — the same tolerance SetInstanceJvmArgsToolTest applies to the same race.
            GameSettings.Effective effective = awaitEffectiveWindowSettings(fx, "Windowed",
                    e -> e.getWidth() == 1280 && e.getHeight() == 720
                            && e.getInheritable(GameSettings::windowTypeProperty) == GameWindowType.WINDOWED);
            assertEquals(1280, effective.getWidth());
            assertEquals(720, effective.getHeight());
            assertEquals(GameWindowType.WINDOWED, effective.getInheritable(GameSettings::windowTypeProperty));

            // The overrideProperties registration is the load-bearing part of this tool (see its
            // class javadoc): without it the write would be silently ignored at launch whenever the
            // parent preset differs.
            GameSettings.Instance setting = fx.repository().getInstanceGameSettings("Windowed");
            assertNotNull(setting, "the write path must have created a per-instance settings object");
            assertTrue(setting.getOverrideProperties().contains(GameSettings.PROPERTY_WINDOW_TYPE),
                    "windowType must be registered as overridden");
            assertTrue(setting.getOverrideProperties().contains(GameSettings.PROPERTY_WIDTH),
                    "width must be registered as overridden");
            assertTrue(setting.getOverrideProperties().contains(GameSettings.PROPERTY_HEIGHT),
                    "height must be registered as overridden");

            // A follow-up report-only call must now reflect the override, not the inherited default.
            ToolResult report = tool.execute(Map.of("instance", "Windowed"));
            assertTrue(report.isSuccess(), "expected success: " + report.getError());
            assertTrue(report.getOutput().contains("1280x720"), "unexpected message: " + report.getOutput());
            assertTrue(report.getOutput().contains("(overridden on this instance)"),
                    "the report must reflect the just-written override: " + report.getOutput());
        }
    }

    /// Polls [org.jackhuang.hmcl.game.HMCLGameRepository#getEffectiveGameSettings] for up to a few
    /// seconds, returning as soon as {@code ready} is satisfied (or the last-observed snapshot on
    /// timeout, so the caller's own assertions still fail with a useful diff instead of this helper
    /// swallowing a genuine mismatch).
    private static GameSettings.Effective awaitEffectiveWindowSettings(
            ProfileFixture fx, String instance, Predicate<GameSettings.Effective> ready) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        GameSettings.Effective last;
        do {
            last = fx.repository().getEffectiveGameSettings(instance);
            if (ready.test(last)) {
                return last;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return last;
    }
}
