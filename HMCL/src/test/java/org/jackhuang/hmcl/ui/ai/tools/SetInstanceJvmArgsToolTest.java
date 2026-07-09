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
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [SetInstanceJvmArgsTool] — the dedicated-tool replacement for hand-editing
/// `instance-game-settings.json`'s JVM/GC args, mirroring [DeleteInstanceTool]'s use of the real
/// [ProfileFixture]-backed instance (no mocks: this tool's only external dependencies are
/// [org.jackhuang.hmcl.setting.Profiles] / [org.jackhuang.hmcl.game.HMCLGameRepository]).
///
/// The actual mutating ("set"/"clear") path runs on the JavaFX Application Thread
/// ([javafx.application.Platform#runLater]), so those cases are gated behind
/// `JavaFXLauncher#isStarted` exactly like [org.jackhuang.hmcl.ui.SVGTest] — they simply don't run
/// in a headless environment where the JavaFX toolkit failed to start, rather than failing the
/// build. The parameter-resolution/report-only/not-found branches all return before that thread
/// hop, so they run unconditionally.
public final class SetInstanceJvmArgsToolTest {

    private final SetInstanceJvmArgsTool tool = new SetInstanceJvmArgsTool();

    @Test
    void isRegisteredUnderTheExpectedToolName() {
        assertEquals("set_instance_jvm_args", tool.getName());
    }

    @Test
    void descriptionStopsShortOfRecommendingRawFileEdits() {
        String description = tool.getDescription();
        assertTrue(description.contains("jvmArgs"), "must document the 'jvmArgs' parameter");
        assertTrue(description.contains("instance-game-settings.json"),
                "must call out the raw settings file by name so the model recognises what NOT to touch");
        assertTrue(description.contains("set_memory"),
                "must point heap-size requests at set_memory instead of accepting -Xmx/-Xms here");
    }

    @Test
    void reportsCurrentValueWithoutMutatingWhenJvmArgsOmitted() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Vanilla");

            ToolResult result = tool.execute(Map.of("instance", "Vanilla"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("no custom JVM args"), "unexpected message: " + result.getOutput());
            assertEquals("", fx.repository().getEffectiveGameSettings("Vanilla").get(GameSettings::jvmOptionsProperty),
                    "a report-only call must not create/mutate the instance's settings");
        }
    }

    @Test
    void failsForNonexistentInstance() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");

            ToolResult result = tool.execute(Map.of("instance", "DoesNotExist", "jvmArgs", "-XX:+UseG1GC"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("does not exist"), "unexpected message: " + result.getError());
        }
    }

    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    void setsReportsAndClearsJvmArgsThroughTheSameInMemorySettingsPath() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Modded");
            String args = "-XX:+UseG1GC -XX:+ParallelRefProcEnabled";

            ToolResult setResult = tool.execute(Map.of("instance", "Modded", "jvmArgs", args));
            assertTrue(setResult.isSuccess(), "expected success: " + setResult.getError());
            assertTrue(setResult.getOutput().contains(args));
            // ProfileFixture's Profiles.init() kicks off ONE background refreshVersionsAsync() the
            // instant the fixture selects its profile (mirroring what real profile selection does);
            // that unrelated, one-shot async scan can still be in flight and briefly reload/clear
            // HMCLGameRepository's in-memory settings map from disk right after our own mutation.
            // Poll for eventual consistency instead of asserting immediately, so this test isn't
            // flaky on that unrelated race — it's not something set_instance_jvm_args itself can
            // control, only something a test observing the SAME shared repository must tolerate.
            assertEquals(args, awaitEffectiveJvmArgs(fx, "Modded", args),
                    "the tool must mutate the SAME in-memory settings object HMCLGameRepository holds");

            ToolResult reportResult = tool.execute(Map.of("instance", "Modded"));
            assertTrue(reportResult.isSuccess());
            assertTrue(reportResult.getOutput().contains(args), "must report the value just set");

            ToolResult clearResult = tool.execute(Map.of("instance", "Modded", "jvmArgs", ""));
            assertTrue(clearResult.isSuccess(), "expected success: " + clearResult.getError());
            assertTrue(clearResult.getOutput().toLowerCase(Locale.ROOT).contains("cleared"),
                    "an explicit empty jvmArgs must clear, not report: " + clearResult.getOutput());
            assertEquals("", awaitEffectiveJvmArgs(fx, "Modded", ""));
        }
    }

    /// Polls the effective JVM-args value for up to a few seconds, returning as soon as it matches
    /// {@code expected} (or the last-observed value on timeout, so the caller's assertion still
    /// fails with a useful diff instead of this helper swallowing a genuine mismatch).
    private static String awaitEffectiveJvmArgs(ProfileFixture fx, String instance, String expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        String last;
        do {
            last = fx.repository().getEffectiveGameSettings(instance).get(GameSettings::jvmOptionsProperty);
            if (expected.equals(last)) {
                return last;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return last;
    }

    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    void advisesUsingSetMemoryWhenJvmArgsMentionsAHeapSizeFlag() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HeapFlagInstance");

            ToolResult result = tool.execute(Map.of("instance", "HeapFlagInstance", "jvmArgs", "-Xmx4096m"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("set_memory"),
                    "expected an advisory note steering back to set_memory: " + result.getOutput());
        }
    }
}
