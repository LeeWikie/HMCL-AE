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
import org.jackhuang.hmcl.setting.VersionIconType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [SetInstanceIconTool] through the real [org.jackhuang.hmcl.game.HMCLGameRepository] over a
/// [ProfileFixture]-backed instance: the metadata contract, every deterministic guard (unknown
/// `iconType`, `iconType`+`imagePath` given together, a missing `imagePath`, an unsupported image
/// extension), the read-only report, the JavaFX-free "already automatic" no-op, and — gated behind
/// `JavaFXLauncher#isStarted` like [SetInstanceJvmArgsToolTest] — a full set-builtin-then-reset-to-auto
/// round trip through the real `GameSettings.Instance` icon property.
///
/// Unlike [RollbackModToolTest]'s `mod` check or [SetInstanceJvmArgsToolTest]'s parameter-resolution
/// branches (which return before any profile access), EVERY branch of [SetInstanceIconTool] —
/// including its parameter guards — runs AFTER `Profiles.getSelectedProfile()` (the tool's very
/// first statement), so none of them can be exercised without a [ProfileFixture] selecting a real
/// profile/instance first. There is deliberately no bare "no profile selected" test here:
/// `ProfileFixture#close()` only restores `Profiles`' static `selectedProfile` property back to
/// `null` when a PRIOR fixture had actually left a non-null value there to restore (see its own
/// javadoc), so whether `Profiles.getSelectedProfile()` throws when called in isolation depends on
/// which test class the shared test JVM happened to run first — not on anything this test controls.
/// Asserting on that would be asserting on test execution order, not on the tool.
///
/// The custom-image copy path (`imagePath` → `repository.setVersionIconFile`) is intentionally NOT
/// exercised: it is deterministic in principle (a plain file copy plus the same DEFAULT-type
/// assignment already covered below), but exercising it would just duplicate the built-in-icon
/// JavaFX round trip without covering a new branch, so it is left to the manual checklist.
public final class SetInstanceIconToolTest {

    private final SetInstanceIconTool tool = new SetInstanceIconTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("set_instance_icon", tool.getName());
        String description = tool.getDescription();
        assertTrue(description.contains("iconType"), "must document the 'iconType' parameter: " + description);
        assertTrue(description.contains("imagePath"), "must document the 'imagePath' parameter: " + description);
        assertTrue(description.contains("auto"), "must document the 'auto' reset value: " + description);
        assertTrue(description.contains("at most one"),
                "must call out that iconType/imagePath are mutually exclusive: " + description);
        assertTrue(description.toLowerCase(Locale.ROOT).contains("report"),
                "must describe the read-only report fallback: " + description);
    }

    @Test
    void mutuallyExclusiveParametersAreRejected() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");

            ToolResult result = tool.execute(Map.of(
                    "instance", "Inst", "iconType", "forge", "imagePath", "/does/not/matter.png"));

            assertFalse(result.isSuccess(), "passing both iconType and imagePath must be rejected");
            assertTrue(result.getError().contains("mutually exclusive"),
                    "unexpected message: " + result.getError());
        }
    }

    @Test
    void unknownIconTypeIsRejectedWithoutMutatingSettings() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");

            ToolResult result = tool.execute(Map.of("instance", "Inst", "iconType", "not_a_real_icon"));

            assertFalse(result.isSuccess(), "an unrecognised icon type must be rejected");
            assertTrue(result.getError().contains("Unknown icon type"),
                    "unexpected message: " + result.getError());
            assertTrue(fx.repository().getVersionIconFile("Inst").isEmpty(),
                    "a rejected write must not leave a custom icon file behind");
        }
    }

    @Test
    void missingImageFileIsRejected() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            Path missing = fx.baseDir().resolve("no-such-icon.png");

            ToolResult result = tool.execute(Map.of("instance", "Inst", "imagePath", missing.toString()));

            assertFalse(result.isSuccess(), "a nonexistent image path must be rejected");
            assertTrue(result.getError().contains("not found"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void unsupportedImageExtensionIsRejected() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            Path notAnImage = fx.baseDir().resolve("notes.txt");
            Files.writeString(notAnImage, "just text, not an image");

            ToolResult result = tool.execute(Map.of("instance", "Inst", "imagePath", notAnImage.toString()));

            assertFalse(result.isSuccess(), "a non-image extension must be rejected");
            assertTrue(result.getError().contains("Unsupported image type"),
                    "unexpected message: " + result.getError());
        }
    }

    @Test
    void reportsCurrentIconWhenNoParametersGiven() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Fresh");

            ToolResult result = tool.execute(Map.of("instance", "Fresh"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("automatic detection"),
                    "a freshly-created instance has no custom icon set: " + result.getOutput());
            assertTrue(result.getOutput().contains("iconType") && result.getOutput().contains("imagePath"),
                    "the report must point back at both write parameters: " + result.getOutput());
        }
    }

    @Test
    void settingAutoIsANoOpWhenTheInstanceAlreadyUsesAutomaticDetection() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Fresh");

            // A fresh instance already defaults to automatic detection, so this hits the no-op fast
            // path BEFORE the JavaFX-thread mutation section — deterministic even without the
            // JavaFX toolkit started.
            ToolResult result = tool.execute(Map.of("instance", "Fresh", "iconType", "auto"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("already uses automatic icon detection; nothing to do"),
                    "unexpected message: " + result.getOutput());
        }
    }

    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    void settingABuiltinIconThenResettingToAutoRoundTripsThroughTheRealRepository() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Modded");

            ToolResult setResult = tool.execute(Map.of("instance", "Modded", "iconType", "forge"));
            assertTrue(setResult.isSuccess(), "expected success: " + setResult.getError());
            assertTrue(setResult.getOutput().contains("forge"), "unexpected message: " + setResult.getOutput());

            GameSettings.Instance afterSet = fx.repository().getInstanceGameSettings("Modded");
            assertNotNull(afterSet, "the tool must create the instance's game-settings object");
            assertEquals(VersionIconType.FORGE, afterSet.iconProperty().getValue(),
                    "the tool must mutate the SAME in-memory settings object HMCLGameRepository holds");

            ToolResult reportResult = tool.execute(Map.of("instance", "Modded"));
            assertTrue(reportResult.isSuccess(), "expected success: " + reportResult.getError());
            assertTrue(reportResult.getOutput().contains("forge"),
                    "must report the icon just set: " + reportResult.getOutput());

            ToolResult resetResult = tool.execute(Map.of("instance", "Modded", "iconType", "auto"));
            assertTrue(resetResult.isSuccess(), "expected success: " + resetResult.getError());
            assertTrue(resetResult.getOutput().contains("automatic detection"),
                    "unexpected message: " + resetResult.getOutput());

            GameSettings.Instance afterReset = fx.repository().getInstanceGameSettings("Modded");
            assertNotNull(afterReset, "the instance's game-settings object must still exist after reset");
            assertEquals(VersionIconType.DEFAULT, afterReset.iconProperty().getValue(),
                    "resetting to 'auto' must set the type back to DEFAULT");
        }
    }
}
