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
import org.jackhuang.hmcl.game.ProcessPriority;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.setting.LauncherVisibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [SetInstanceLaunchBehaviorTool] through the real [ProfileFixture]-backed instance (no
/// mocks: the tool's only external dependencies are [org.jackhuang.hmcl.setting.Profiles] /
/// [org.jackhuang.hmcl.game.HMCLGameRepository] / [GameSettings]), mirroring
/// [SetInstanceJvmArgsToolTest]'s report/reject/write-with-override coverage.
///
/// Unlike [RollbackModTool] (which validates its `mod` parameter BEFORE touching
/// [org.jackhuang.hmcl.setting.Profiles]), this tool resolves the profile, repository and target
/// instance FIRST and only parses/validates `launcherVisibility` / `processPriority` / the boolean
/// flags afterwards (see `SetInstanceLaunchBehaviorTool#execute`). So there is no guard branch that
/// can be exercised without a real (fixture-backed) instance — every test below, including the
/// invalid-enum and invalid-boolean guards, goes through [ProfileFixture] to reach that validation
/// code at all, exactly like every branch in [SetInstanceJvmArgsToolTest].
///
/// The actual mutating write runs on the JavaFX Application Thread ([javafx.application.Platform#runLater]),
/// so that case is gated behind `JavaFXLauncher#isStarted` exactly like [SetInstanceJvmArgsToolTest] —
/// it simply doesn't run in a headless environment where the JavaFX toolkit failed to start, rather
/// than failing the build. The parameter-resolution/report-only/not-found/invalid-value branches all
/// return before that thread hop, so they run unconditionally.
///
/// NOT covered here (left to the manual test checklist): the read-only-game-settings-file rejection
/// (`repository.isInstanceGameSettingsReadOnly`) and the JavaFX-runtime-unavailable / apply-timeout
/// branches, both of which need an adversarial environment (a read-only settings file on disk, or a
/// toolkit that starts but then hangs) rather than anything a deterministic unit test can cheaply
/// arrange.
public final class SetInstanceLaunchBehaviorToolTest {

    private final SetInstanceLaunchBehaviorTool tool = new SetInstanceLaunchBehaviorTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("set_launch_behavior", tool.getName());
        String description = tool.getDescription();
        assertTrue(description.contains("launcherVisibility"), "must document 'launcherVisibility': " + description);
        assertTrue(description.contains("processPriority"), "must document 'processPriority': " + description);
        assertTrue(description.contains("close, hide, keep, hide_and_reopen"),
                "must list the accepted launcherVisibility values: " + description);
        assertTrue(description.contains("low, below_normal, normal, above_normal, high"),
                "must list the accepted processPriority values: " + description);
        assertTrue(description.contains("WRITES"), "must disclose the write permission level: " + description);
        assertTrue(description.contains("REPORTS"),
                "must document the no-parameters read-only report fallback: " + description);
    }

    @Test
    void rejectsInvalidLauncherVisibilityValue() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Vanilla");

            ToolResult result = tool.execute(Map.of("instance", "Vanilla", "launcherVisibility", "sideways"));

            assertFalse(result.isSuccess(), "an unknown launcherVisibility value must be rejected");
            assertTrue(result.getError().contains("launcherVisibility"),
                    "the failure must name the offending parameter: " + result.getError());
            assertTrue(result.getError().contains("close, hide, keep, hide_and_reopen"),
                    "the failure must list the valid values: " + result.getError());
            assertTrue(result.getError().contains("Retryable: yes"), "a bad enum value is retryable: " + result.getError());
        }
    }

    @Test
    void rejectsInvalidProcessPriorityValue() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Vanilla");

            ToolResult result = tool.execute(Map.of("instance", "Vanilla", "processPriority", "turbo"));

            assertFalse(result.isSuccess(), "an unknown processPriority value must be rejected");
            assertTrue(result.getError().contains("processPriority"),
                    "the failure must name the offending parameter: " + result.getError());
            assertTrue(result.getError().contains("low, below_normal, normal, above_normal, high"),
                    "the failure must list the valid values: " + result.getError());
        }
    }

    @Test
    void rejectsNonBooleanFlagValue() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Vanilla");

            ToolResult result = tool.execute(Map.of("instance", "Vanilla", "allowAutoAgent", "maybe"));

            assertFalse(result.isSuccess(), "a non-boolean value for a boolean flag must be rejected");
            assertTrue(result.getError().contains("allowAutoAgent"),
                    "the failure must name the offending parameter: " + result.getError());
            assertTrue(result.getError().toLowerCase(Locale.ROOT).contains("boolean"),
                    "the failure must explain a boolean is required: " + result.getError());
        }
    }

    @Test
    void failsForNonexistentInstance() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");

            ToolResult result = tool.execute(Map.of("instance", "DoesNotExist", "launcherVisibility", "hide"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("does not exist"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void reportsEffectiveValuesWithoutMutatingWhenNoParametersGiven() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Vanilla");

            ToolResult result = tool.execute(Map.of("instance", "Vanilla"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            String output = result.getOutput();
            // GameSettings' declared defaults: launcherVisibility=HIDE, processPriority=NORMAL, all
            // boolean flags=false — and nothing has been overridden by this instance yet.
            assertTrue(output.contains("launcherVisibility = hide (inherited from preset)"), output);
            assertTrue(output.contains("processPriority = normal (inherited from preset)"), output);
            assertTrue(output.contains("allowAutoAgent = false (inherited from preset)"), output);
            assertTrue(output.contains("showLogs = false (inherited from preset)"), output);
            assertTrue(output.contains("To change any of these"), output);

            GameSettings.Instance existing = fx.repository().getEffectiveGameSettings("Vanilla").getInstance();
            assertTrue(existing == null || !existing.getOverrideProperties().contains(GameSettings.PROPERTY_LAUNCHER_VISIBILITY),
                    "a report-only call must not register any override");
        }
    }

    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    void setsAndReportsLaunchBehaviorThroughTheSameInMemorySettingsPath() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Modded");

            ToolResult setResult = tool.execute(Map.of(
                    "instance", "Modded",
                    "launcherVisibility", "hide_and_reopen",
                    "processPriority", "high",
                    "allowAutoAgent", "true",
                    "showLogs", "true"));

            assertTrue(setResult.isSuccess(), "expected success: " + setResult.getError());
            String setOutput = setResult.getOutput();
            assertTrue(setOutput.contains("launcherVisibility: hide (was inherited) -> hide_and_reopen"), setOutput);
            assertTrue(setOutput.contains("processPriority: normal (was inherited) -> high"), setOutput);
            assertTrue(setOutput.contains("allowAutoAgent: false (was inherited) -> true"), setOutput);
            assertTrue(setOutput.contains("showLogs: false (was inherited) -> true"), setOutput);
            assertTrue(setOutput.toLowerCase(Locale.ROOT).contains("overridden"), setOutput);

            // ProfileFixture's Profiles.init() kicks off ONE background refreshVersionsAsync() the
            // instant the fixture selects its profile; that unrelated one-shot async scan can still be
            // in flight and briefly reload/clear HMCLGameRepository's in-memory settings map right
            // after our own mutation. Poll for eventual consistency instead of asserting immediately,
            // exactly like SetInstanceJvmArgsToolTest does for the same reason.
            assertEquals(LauncherVisibility.HIDE_AND_REOPEN,
                    awaitEffectiveLauncherVisibility(fx, "Modded", LauncherVisibility.HIDE_AND_REOPEN),
                    "the tool must mutate the SAME in-memory settings object HMCLGameRepository holds");

            GameSettings.Effective effective = fx.repository().getEffectiveGameSettings("Modded");
            assertEquals(ProcessPriority.HIGH, effective.getInheritable(GameSettings::processPriorityProperty));
            assertEquals(Boolean.TRUE, effective.getInheritable(GameSettings::allowAutoAgentProperty));
            assertEquals(Boolean.TRUE, effective.getInheritable(GameSettings::showLogsProperty));

            GameSettings.Instance existing = effective.getInstance();
            assertNotNull(existing, "a write must create the per-instance settings object");
            assertTrue(existing.getOverrideProperties().contains(GameSettings.PROPERTY_LAUNCHER_VISIBILITY));
            assertTrue(existing.getOverrideProperties().contains(GameSettings.PROPERTY_PROCESS_PRIORITY));
            assertTrue(existing.getOverrideProperties().contains(GameSettings.PROPERTY_ALLOW_AUTO_AGENT));
            assertTrue(existing.getOverrideProperties().contains(GameSettings.PROPERTY_SHOW_LOGS));

            ToolResult reportResult = tool.execute(Map.of("instance", "Modded"));
            assertTrue(reportResult.isSuccess(), "expected success: " + reportResult.getError());
            String reportOutput = reportResult.getOutput();
            assertTrue(reportOutput.contains("launcherVisibility = hide_and_reopen (overridden by this instance)"), reportOutput);
            assertTrue(reportOutput.contains("processPriority = high (overridden by this instance)"), reportOutput);
            assertTrue(reportOutput.contains("allowAutoAgent = true (overridden by this instance)"), reportOutput);
        }
    }

    /// Polls the effective launcher-visibility value for up to a few seconds, returning as soon as it
    /// matches {@code expected} (or the last-observed value on timeout, so the caller's assertion
    /// still fails with a useful diff instead of this helper swallowing a genuine mismatch). See the
    /// comment in [#setsAndReportsLaunchBehaviorThroughTheSameInMemorySettingsPath] for why this poll
    /// exists at all.
    private static LauncherVisibility awaitEffectiveLauncherVisibility(
            ProfileFixture fx, String instance, LauncherVisibility expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        LauncherVisibility last;
        do {
            last = fx.repository().getEffectiveGameSettings(instance).getInheritable(GameSettings::launcherVisibilityProperty);
            if (expected.equals(last)) {
                return last;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return last;
    }
}
