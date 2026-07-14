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
import org.jackhuang.hmcl.setting.JavaVersionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [SetInstanceJavaTool]'s metadata contract, its per-mode parameter guards, and the
/// report-only / `mode=auto` / `mode=custom` write branches through a real [ProfileFixture]-backed
/// instance — no mocks: the tool's only external dependencies are
/// [org.jackhuang.hmcl.setting.Profiles] / [org.jackhuang.hmcl.game.HMCLGameRepository] /
/// [org.jackhuang.hmcl.java.JavaManager].
///
/// Unlike [RollbackModTool], this tool resolves `Profiles.getSelectedProfile()` and the target
/// instance BEFORE it looks at `mode` at all, so every branch below — including the pure
/// parameter guards — needs a [ProfileFixture] instance selected first; there is no guard that
/// fires ahead of profile/instance resolution the way [RollbackModTool]'s missing-`mod` check
/// does. This mirrors [SetInstanceJvmArgsToolTest], the closest sibling ("Set instance config"
/// tool), which takes the same approach.
///
/// ### What is intentionally NOT exercised here (manual checklist only)
/// - **The `mode=version` "any currently-detected Java matches?" advisory** and **the entire
///   `mode=detected` match/no-match path** both unconditionally call
///   [org.jackhuang.hmcl.java.JavaManager#getAllJava()], which blocks on a `CountDownLatch` that
///   only [org.jackhuang.hmcl.java.JavaManager#initialize()] ever counts down — and nothing in
///   this test module calls `initialize()` (grep confirms it), so reaching either of those
///   branches in a unit test would hang the JVM, not fail it. Only their up-front parameter
///   guards (missing/blank `version`, non-positive `version`, missing `path`) are covered; the
///   full `version`/`detected` match paths need a real launcher bootstrap (which calls
///   `JavaManager.initialize()` from `Controllers`) and belong to a manual test pass.
/// - The report path's "effective java" resolution line
///   ([GameSettings.Effective#getJava]) is itself guarded by the tool on
///   `JavaManager.isInitialized()`, which is always `false` in this headless test JVM for the
///   same reason — so the report assertions below only pin the safe, JavaManager-independent
///   parts of the report text.
public final class SetInstanceJavaToolTest {

    private final SetInstanceJavaTool tool = new SetInstanceJavaTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("set_instance_java", tool.getName());
        String description = tool.getDescription();
        assertTrue(description.contains("auto|version|detected|custom"),
                "the description must advertise the four mode literals: " + description);
        assertTrue(description.contains("WRITES"), "must call out that this WRITES settings: " + description);
        assertTrue(description.contains("REPORTS"),
                "must document the mode-omitted report-only degrade: " + description);
        assertTrue(description.contains("list_java"),
                "must point mode=detected callers at list_java to discover valid paths: " + description);
    }

    @Test
    void invalidModeValueIsRejected() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");

            ToolResult result = tool.execute(Map.of("instance", "Inst", "mode", "bogus"));

            assertFalse(result.isSuccess(), "an unknown mode must fail");
            assertTrue(result.getError().contains("auto|version|detected|custom"),
                    "the failure must list the valid modes: " + result.getError());
            assertTrue(result.getError().contains("bogus"),
                    "the failure must echo back the bad value: " + result.getError());
        }
    }

    @Test
    void modeVersionRequiresAVersionParameter() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");

            ToolResult result = tool.execute(Map.of("instance", "Inst", "mode", "version"));

            assertFalse(result.isSuccess(), "mode=version with no 'version' must fail");
            assertTrue(result.getError().contains("requires a 'version' parameter"),
                    "unexpected message: " + result.getError());
        }
    }

    @Test
    void modeVersionRejectsANonPositiveVersion() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");

            ToolResult result = tool.execute(Map.of("instance", "Inst", "mode", "version", "version", 0));

            assertFalse(result.isSuccess(), "a non-positive major version must be rejected");
            assertTrue(result.getError().contains("positive integer"),
                    "unexpected message: " + result.getError());
        }
    }

    @Test
    void modeCustomRequiresAPathParameter() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");

            ToolResult result = tool.execute(Map.of("instance", "Inst", "mode", "custom"));

            assertFalse(result.isSuccess(), "mode=custom with no 'path' must fail");
            assertTrue(result.getError().contains("requires a 'path' parameter"),
                    "unexpected message: " + result.getError());
        }
    }

    @Test
    void modeCustomRejectsAPathThatDoesNotExist() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            String missing = fx.baseDir().resolve("no-such-java-here").toString();

            ToolResult result = tool.execute(Map.of("instance", "Inst", "mode", "custom", "path", missing));

            assertFalse(result.isSuccess(), "a nonexistent custom path must be rejected");
            assertTrue(result.getError().contains("does not point to an existing file"),
                    "unexpected message: " + result.getError());
        }
    }

    @Test
    void modeDetectedRequiresAPathParameter() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");

            ToolResult result = tool.execute(Map.of("instance", "Inst", "mode", "detected"));

            assertFalse(result.isSuccess(), "mode=detected with no 'path' must fail");
            assertTrue(result.getError().contains("requires a 'path' parameter"),
                    "unexpected message: " + result.getError());
        }
    }

    @Test
    void reportsCurrentSelectionWithoutMutatingWhenModeOmitted() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Vanilla");

            ToolResult result = tool.execute(Map.of("instance", "Vanilla"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            String output = result.getOutput();
            assertTrue(output.contains("mode: AUTO"), "unexpected message: " + output);
            assertTrue(output.contains("(inherited from the parent preset)"), "unexpected message: " + output);
            assertTrue(output.contains("HMCL picks a suitable Java for the game version automatically"),
                    "unexpected message: " + output);
            assertTrue(output.contains("effective java:"), "unexpected message: " + output);
            assertNull(fx.repository().getInstanceGameSettings("Vanilla"),
                    "a report-only call must not create/mutate the instance's game-settings object");
        }
    }

    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    void setsAutoModeAndMarksJavaTypeOverridden() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Modded");

            ToolResult result = tool.execute(Map.of("instance", "Modded", "mode", "auto"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("Set the Java selection"), "unexpected message: " + result.getOutput());
            assertTrue(result.getOutput().contains("automatic"), "unexpected message: " + result.getOutput());

            assertEquals(JavaVersionType.AUTO, awaitEffectiveJavaType(fx, "Modded", JavaVersionType.AUTO));
            GameSettings.Instance setting = fx.repository().getInstanceGameSettings("Modded");
            assertTrue(setting != null && setting.getOverrideProperties().contains(GameSettings.PROPERTY_JAVA_TYPE),
                    "javaType must be registered as an instance override, or the write is a silent launch-time no-op");

            ToolResult reportResult = tool.execute(Map.of("instance", "Modded"));
            assertTrue(reportResult.isSuccess());
            assertTrue(reportResult.getOutput().contains("(set on this instance)"),
                    "the report must now show the override as instance-set, not inherited: " + reportResult.getOutput());
        }
    }

    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    void setsCustomModeToAnExplicitExecutablePath() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("CustomJavaInst");
            Path fakeJava = fx.baseDir().resolve("fake-java-binary");
            Files.writeString(fakeJava, "not a real java binary, just needs to exist");
            String path = fakeJava.toString();

            ToolResult result = tool.execute(Map.of("instance", "CustomJavaInst", "mode", "custom", "path", path));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("custom executable"), "unexpected message: " + result.getOutput());
            assertTrue(result.getOutput().contains(path), "unexpected message: " + result.getOutput());

            assertEquals(JavaVersionType.CUSTOM, awaitEffectiveJavaType(fx, "CustomJavaInst", JavaVersionType.CUSTOM));
            assertEquals(path,
                    fx.repository().getEffectiveGameSettings("CustomJavaInst").getInheritable(GameSettings::customJavaPathProperty),
                    "the tool must write the SAME in-memory settings object HMCLGameRepository holds");
            GameSettings.Instance setting = fx.repository().getInstanceGameSettings("CustomJavaInst");
            assertTrue(setting != null
                            && setting.getOverrideProperties().contains(GameSettings.PROPERTY_JAVA_TYPE)
                            && setting.getOverrideProperties().contains(GameSettings.PROPERTY_CUSTOM_JAVA_PATH),
                    "both javaType and customJavaPath must be registered as instance overrides");
        }
    }

    /// Polls the effective Java-type value for up to a few seconds, returning as soon as it
    /// matches {@code expected} (or the last-observed value on timeout, so the caller's assertion
    /// still fails with a useful diff). Mirrors [SetInstanceJvmArgsToolTest]'s
    /// `awaitEffectiveJvmArgs`: [ProfileFixture]'s one-shot background `refreshVersionsAsync()`
    /// can briefly reload the repository's in-memory settings map from disk right after our own
    /// synchronous mutation, which is a race the test must tolerate, not the tool's problem.
    private static JavaVersionType awaitEffectiveJavaType(ProfileFixture fx, String instance, JavaVersionType expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        JavaVersionType last;
        do {
            last = fx.repository().getEffectiveGameSettings(instance).getInheritable(GameSettings::javaTypeProperty);
            if (expected == last) {
                return last;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return last;
    }
}
