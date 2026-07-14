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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [SetInstanceLaunchArgsTool] — the dedicated-tool replacement for hand-editing the
/// `gameArguments` / `environmentVariables` / `noJVMOptions` / `noOptimizingJVMOptions` /
/// `notCheckJVM` group of `instance-game-settings.json`, through the real [ProfileFixture]-backed
/// instance (no mocks: this tool's only external dependencies are
/// [org.jackhuang.hmcl.setting.Profiles] / [org.jackhuang.hmcl.game.HMCLGameRepository]).
///
/// Unlike [RollbackModTool] (`mod` is required and validated before any [org.jackhuang.hmcl.setting.Profiles]
/// access) or [DeleteInstanceTool], EVERY parameter of this tool is optional — that is the whole
/// point of its read/write duality (see the tool's class doc). There is therefore no
/// "missing-required-parameter" branch that returns before `Profiles.getSelectedProfile()` runs:
/// the very first line of {@code execute} always resolves the selected profile. A test that wants
/// to exercise a rejection (an invalid boolean value, an unknown instance id, …) unavoidably needs
/// a live profile, so — mirroring [SetInstanceJvmArgsToolTest], the actual paradigm for this
/// family of "Set*" tools in this codebase, which has the exact same shape and also has no
/// fixture-free rejection test — every non-metadata test below goes through [ProfileFixture].
///
/// The actual mutating ("set") path runs on the JavaFX Application Thread
/// ([javafx.application.Platform#runLater]), so those cases are gated behind
/// `JavaFXLauncher#isStarted` exactly like [SetInstanceJvmArgsToolTest] — they simply don't run in
/// a headless environment where the JavaFX toolkit failed to start, rather than failing the build.
/// The report-only, invalid-boolean-value, and unknown-instance branches all return before that
/// thread hop, so they run unconditionally.
///
/// NOT covered here (left to the manual test checklist): the interaction between this tool and a
/// concurrently-running game process (`GameResourceGuard`), and instances whose settings file is
/// genuinely read-only on disk (`isInstanceGameSettingsReadOnly`) — both need real OS-level file
/// state a unit test cannot cheaply fabricate.
public final class SetInstanceLaunchArgsToolTest {

    private final SetInstanceLaunchArgsTool tool = new SetInstanceLaunchArgsTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("set_instance_launch_args", tool.getName());
        String description = tool.getDescription();
        assertTrue(description.contains("gameArguments"), "must document the 'gameArguments' parameter: " + description);
        assertTrue(description.contains("environmentVariables"), "must document the 'environmentVariables' parameter: " + description);
        assertTrue(description.contains("noJVMOptions"), "must document the 'noJVMOptions' parameter: " + description);
        assertTrue(description.contains("set_jvm_args"),
                "must point custom JVM/GC flags at set_jvm_args instead of accepting them here: " + description);
        assertTrue(description.contains("set_memory"),
                "must point heap-size requests at set_memory instead of accepting them here: " + description);
    }

    @Test
    void reportsEffectiveValuesWithoutMutatingWhenNoFieldSupplied() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Vanilla");

            ToolResult result = tool.execute(Map.of("instance", "Vanilla"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            String output = result.getOutput();
            assertTrue(output.contains("Launch-args settings of instance 'Vanilla'"), "unexpected message: " + output);
            assertTrue(output.contains("gameArguments"), "must list gameArguments in the report: " + output);
            assertTrue(output.contains("[inherited from preset]"),
                    "an untouched instance must report every field as inherited: " + output);
            assertFalse(output.contains("[overridden by this instance]"),
                    "an untouched instance must not claim any override yet: " + output);

            assertNull(fx.repository().getInstanceGameSettings("Vanilla"),
                    "a report-only call must not create the instance's per-instance settings object");
        }
    }

    @Test
    void rejectsNonBooleanNoJVMOptionsValueWithoutMutatingAnything() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Vanilla");

            ToolResult result = tool.execute(Map.of("instance", "Vanilla", "noJVMOptions", "not-a-boolean"));

            assertFalse(result.isSuccess(), "a non-boolean 'noJVMOptions' value must be rejected");
            String error = result.getError().toLowerCase(Locale.ROOT);
            assertTrue(error.contains("nojvmoptions"), "the failure must name the offending parameter: " + result.getError());
            assertTrue(error.contains("boolean"), "the failure must explain the expected type: " + result.getError());

            assertNull(fx.repository().getInstanceGameSettings("Vanilla"),
                    "a rejected write must not create/mutate the instance's per-instance settings");
        }
    }

    @Test
    void failsForNonexistentInstance() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");

            ToolResult result = tool.execute(Map.of("instance", "DoesNotExist", "gameArguments", "--width 1280"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("does not exist"), "unexpected message: " + result.getError());
        }
    }

    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    void setsReportsAndClearsGameArgumentsThroughTheSameInMemorySettingsPath() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Modded");
            String args = "--width 1280 --height 720";

            ToolResult setResult = tool.execute(Map.of("instance", "Modded", "gameArguments", args));
            assertTrue(setResult.isSuccess(), "expected success: " + setResult.getError());
            assertTrue(setResult.getOutput().contains(args), "unexpected message: " + setResult.getOutput());

            // Captured immediately, before any polling below: execute() only returns after the
            // Platform.runLater task fully ran (it blocks on a CountDownLatch), so the override
            // registration is already in place on this exact in-memory object the instant setResult
            // comes back — independently of any later background reload racing on the shared map.
            GameSettings.Instance persisted = fx.repository().getInstanceGameSettings("Modded");
            assertTrue(persisted.getOverrideProperties().contains(GameSettings.PROPERTY_GAME_ARGS),
                    "gameArguments must be registered as a per-instance override, or it silently keeps "
                            + "launching with the inherited preset value");

            // Mirrors SetInstanceJvmArgsToolTest's polling helper: ProfileFixture's Profiles.init()
            // kicks off a one-shot background refreshVersionsAsync() the instant the fixture selects
            // its profile, which can still be in flight and briefly reload HMCLGameRepository's
            // in-memory settings map from disk right after our own mutation.
            assertEquals(args, awaitEffectiveGameArguments(fx, "Modded", args),
                    "the tool must mutate the SAME in-memory settings object HMCLGameRepository holds");

            ToolResult reportResult = tool.execute(Map.of("instance", "Modded"));
            assertTrue(reportResult.isSuccess());
            assertTrue(reportResult.getOutput().contains(args), "must report the value just set");
            assertTrue(reportResult.getOutput().contains("[overridden by this instance]"),
                    "the report must now reflect the override: " + reportResult.getOutput());

            ToolResult clearResult = tool.execute(Map.of("instance", "Modded", "gameArguments", ""));
            assertTrue(clearResult.isSuccess(), "expected success: " + clearResult.getError());
            // Fetched fresh (not reusing 'persisted' above) so this holds even if a background
            // reload replaced the map's entry between the two writes.
            GameSettings.Instance persistedAfterClear = fx.repository().getInstanceGameSettings("Modded");
            // An explicit empty string is still a SUPPLIED value (a deliberate clear), so the override
            // registration stays in place — only the stored value becomes empty.
            assertTrue(persistedAfterClear.getOverrideProperties().contains(GameSettings.PROPERTY_GAME_ARGS),
                    "clearing to an empty string is still an explicit override, not a return to 'inherited'");
            assertEquals("", awaitEffectiveGameArguments(fx, "Modded", ""));
        }
    }

    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    void advisesThatNoOptimizingJVMOptionsHasNoEffectWhileNoJVMOptionsIsOn() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Advisory");

            ToolResult result = tool.execute(Map.of(
                    "instance", "Advisory",
                    "noJVMOptions", true,
                    "noOptimizingJVMOptions", true));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("no additional effect"),
                    "expected the advisory note steering away from a redundant combination: " + result.getOutput());
        }
    }

    /// Polls the effective gameArguments value for up to a few seconds, returning as soon as it
    /// matches {@code expected} (or the last-observed value on timeout, so the caller's assertion
    /// still fails with a useful diff instead of this helper swallowing a genuine mismatch).
    private static String awaitEffectiveGameArguments(ProfileFixture fx, String instance, String expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        String last;
        do {
            last = fx.repository().getEffectiveGameSettings(instance).get(GameSettings::gameArgumentsProperty);
            if (expected.equals(last)) {
                return last;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return last;
    }
}
