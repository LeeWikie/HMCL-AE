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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [ManageJavaTool]'s deterministic contract and guard branches: the metadata/description
/// contract, the unknown-`operation` guard (exercised both through the `operation` parameter and
/// its `mode` alias), and `add`'s two path-validation branches (`path` missing; `path` present but
/// not resolvable to a `java`/`java.exe` executable). All of these return before the tool ever
/// touches [org.jackhuang.hmcl.java.JavaManager], so — unlike most other AI-tool tests in this
/// package — none of them need [ProfileFixture]: this tool has no dependency on
/// [org.jackhuang.hmcl.setting.Profiles] or [org.jackhuang.hmcl.game.HMCLGameRepository] at all
/// (it takes no `instance` parameter); its only collaborator is the static
/// [org.jackhuang.hmcl.java.JavaManager] registry.
///
/// Every OTHER branch — `report`'s and `refresh`'s success output, `add`'s "already registered" /
/// successful-registration paths, and every branch of `uninstall` (including the deterministic
/// "no managed runtimes" case the task brief flags as a candidate) — is intentionally NOT exercised
/// here, because all of them reach `ManageJavaTool`'s `safeGetAll()` helper, which calls
/// `JavaManager.getAllJava()`. That method blocks on a `CountDownLatch` until
/// `JavaManager.initialize()` has run at least once in this JVM — a real, one-time, whole-machine
/// Java discovery scan (Windows registry queries, filesystem walks under `Program Files`, `PATH`,
/// `~/.jdks`, etc., plus `SettingsManager`/`CacheRepository` access) that only the app's real
/// startup path ([org.jackhuang.hmcl.Launcher]) or the CLI test harness currently perform in the
/// right order. Calling `JavaManager.initialize()` ad hoc from a bare unit test reproduces exactly
/// the "`list_java` headless permanent hang" class of bug this codebase has already hit once
/// (see `开发记录/会话纪要/05-2026-06-29_30-CLI测试台与NBT安全.md`) if the bootstrap order is even
/// slightly off, and — even when it does complete — its result (which real Java installations this
/// particular machine happens to have) is exactly the kind of real-environment-dependent outcome
/// the test-writing brief asks to leave to the manual checklist rather than assert on. No test in
/// this class calls `JavaManager.initialize()` or otherwise triggers `getAllJava()`, so none of
/// them can hang for this reason.
public final class ManageJavaToolTest {

    private final ManageJavaTool tool = new ManageJavaTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("manage_java", tool.getName());
        String description = tool.getDescription();
        assertTrue(description.contains("list_java") && description.contains("download_java"),
                "the description must position this tool against its list/download counterparts: " + description);
        assertTrue(description.contains("refresh") && description.contains("uninstall"),
                "the description must document the 'refresh' and 'uninstall' operations: " + description);
        assertTrue(description.contains("javaVersion"),
                "the description must document the 'javaVersion' parameter used to target uninstall: " + description);
        assertTrue(description.contains("DANGEROUS"),
                "the description must flag uninstall's destructive nature: " + description);
    }

    @Test
    void unknownOperationIsRejectedWithoutTouchingJavaManager() {
        // The operation switch's default branch runs before any JavaManager access, so this needs
        // neither a fixture nor JavaManager.initialize().
        ToolResult result = tool.execute(Map.of("operation", "reformat"));

        assertFalse(result.isSuccess(), "an unrecognised operation must fail");
        String error = result.getError();
        assertTrue(error.contains("reformat"), "the failure must echo back the bad operation: " + error);
        assertTrue(error.toLowerCase(Locale.ROOT).contains("refresh"),
                "the failure must point at the supported operations, including 'refresh': " + error);
    }

    @Test
    void unknownOperationIsAlsoRejectedThroughTheModeAlias() {
        // 'operation' is optional; when absent the tool falls back to reading 'mode' instead. Cover
        // that alias explicitly so a regression that drops the fallback doesn't go unnoticed.
        ToolResult result = tool.execute(Map.of("mode", "explode"));

        assertFalse(result.isSuccess(), "an unrecognised 'mode' value must fail the same way");
        assertTrue(result.getError().contains("explode"),
                "the failure must echo back the bad value read from 'mode': " + result.getError());
    }

    @Test
    void addMissingPathIsRejectedWithoutTouchingAnyProfileOrJavaManager() {
        // 'path' is validated first, before resolveJavaBinary() or any JavaManager access.
        ToolResult result = tool.execute(Map.of("operation", "add"));

        assertFalse(result.isSuccess(), "add with no 'path' must fail");
        assertTrue(result.getError().toLowerCase(Locale.ROOT).contains("path"),
                "the failure must name the missing 'path' parameter: " + result.getError());
    }

    @Test
    void addPathThatIsNotAJavaHomeIsRejectedBeforeTouchingJavaManager(@TempDir Path notAJavaHome) throws Exception {
        // A real directory that is neither a java/java.exe executable nor a JDK/JRE home (no
        // bin/java(.exe) under it): resolveJavaBinary() returns null and the tool fails right there,
        // still without ever calling JavaManager.getAllJava().
        Files.writeString(notAJavaHome.resolve("readme.txt"), "definitely not a JDK");

        ToolResult result = tool.execute(Map.of("operation", "add", "path", notAJavaHome.toString()));

        assertFalse(result.isSuccess(),
                "a directory with no bin/java(.exe) must be rejected, got: " + result.getOutput());
        assertTrue(result.getError().contains("Could not find a Java executable"),
                "the failure must explain no Java executable was found: " + result.getError());
    }

    @Test
    void addPathThatIsNotAValidFilesystemPathIsRejected() {
        // Paths.get() throws InvalidPathException on an embedded NUL character (invalid on both
        // Windows and POSIX filesystems) before resolveJavaBinary() -- and therefore any
        // JavaManager access -- runs. A NUL is used (rather than e.g. '?'/'*') because those are
        // only reserved on Windows; NUL keeps this test's outcome the same on every platform.
        String notAPath = "C:\\invalid" + '\0' + "path";

        ToolResult result = tool.execute(Map.of("operation", "add", "path", notAPath));

        assertFalse(result.isSuccess(), "an unparsable filesystem path must be rejected");
        assertTrue(result.getError().contains("not a valid filesystem path"),
                "the failure must explain the path itself is malformed: " + result.getError());
    }
}
