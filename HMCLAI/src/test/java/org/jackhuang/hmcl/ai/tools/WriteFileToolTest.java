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
package org.jackhuang.hmcl.ai.tools;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// WriteFileTool is a write-capable, higher-risk tool with no confirmation dialog by default
/// (CONTROLLED_WRITE) — this had zero automated verification of its root-containment logic before.
/// A toRealPath()-based symlink-containment recheck applies to every write, mirroring EditTool's
/// fix — see {@link #overwritingThroughASymlinkEscapingEveryRootIsRejected}. Crucially this check
/// is NOT skipped when the target file does not exist yet ({@link Path#toRealPath()} requires the
/// path to exist, so a naive implementation is tempted to skip the check for "create a new file",
/// which is exactly the case a directory symlink/junction escape (buildable with plain user rights
/// on Windows via `mklink /J`) would exploit — see
/// {@link #creatingANewFileThroughADirectoryLinkEscapingEveryRootIsRejected} and
/// {@link #creatingANewFileNestedSeveralLevelsBelowADirectoryLinkEscapingEveryRootIsRejected}.
public final class WriteFileToolTest {

    @Test
    public void writesFileUnderPrimaryRoot(@TempDir Path root) throws IOException {
        WriteFileTool tool = new WriteFileTool(root);

        ToolResult result = tool.execute(Map.of("path", "notes.txt", "content", "hello"));

        assertTrue(result.isSuccess(), "expected success: " + result.getError());
        assertEquals("hello", Files.readString(root.resolve("notes.txt"), StandardCharsets.UTF_8));
    }

    @Test
    public void writesUnderASecondaryAddedRoot(@TempDir Path primary, @TempDir Path secondary) throws IOException {
        WriteFileTool tool = new WriteFileTool(primary);
        tool.addRoot(secondary);

        ToolResult result = tool.execute(Map.of(
                "path", secondary.resolve("out.txt").toString(), "content", "from secondary root"));

        assertTrue(result.isSuccess(), "expected success: " + result.getError());
        assertEquals("from secondary root", Files.readString(secondary.resolve("out.txt"), StandardCharsets.UTF_8));
    }

    @Test
    public void absolutePathOutsideAllRootsIsRejected(@TempDir Path root, @TempDir Path outside) {
        WriteFileTool tool = new WriteFileTool(root);

        ToolResult result = tool.execute(Map.of(
                "path", outside.resolve("evil.txt").toString(), "content", "should not land here"));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("outside the allowed roots"));
        assertFalse(Files.exists(outside.resolve("evil.txt")));
    }

    @Test
    public void relativeTraversalOutsideRootIsRejected(@TempDir Path root) throws IOException {
        // A subdirectory root so "../../outside.txt" actually walks past the root, not just to a
        // sibling still inside it.
        Path sub = root.resolve("sub").resolve("dir");
        Files.createDirectories(sub);
        WriteFileTool tool = new WriteFileTool(sub);

        ToolResult result = tool.execute(Map.of(
                "path", "../../../outside.txt", "content", "escape attempt"));

        assertFalse(result.isSuccess(), "a relative path walking outside the root must be rejected");
    }

    @Test
    public void appendTrueAppendsInsteadOfOverwriting(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("log.txt"), "line1\n");
        WriteFileTool tool = new WriteFileTool(root);

        ToolResult result = tool.execute(Map.of("path", "log.txt", "content", "line2\n", "append", true));

        assertTrue(result.isSuccess(), "expected success: " + result.getError());
        assertEquals("line1\nline2\n", Files.readString(root.resolve("log.txt"), StandardCharsets.UTF_8));
    }

    @Test
    public void overwritingThroughASymlinkEscapingEveryRootIsRejected(@TempDir Path root, @TempDir Path outside) {
        Path secretFile = outside.resolve("secret.txt");
        Path link = root.resolve("escape.txt");
        try {
            Files.writeString(secretFile, "original-secret-content");
            Files.createSymbolicLink(link, secretFile);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symlink creation is not permitted in this environment: " + e);
            return;
        }

        WriteFileTool tool = new WriteFileTool(root);
        ToolResult result = tool.execute(Map.of("path", "escape.txt", "content", "TAMPERED"));

        assertFalse(result.isSuccess(), "writing through a symlink escaping every allowed root must be refused");
        try {
            assertEquals("original-secret-content", Files.readString(secretFile),
                    "the file outside the allowed roots must be untouched");
        } catch (IOException e) {
            fail(e);
        }
    }

    @Test
    public void appendingThroughASymlinkEscapingEveryRootIsRejected(@TempDir Path root, @TempDir Path outside) {
        Path secretFile = outside.resolve("secret2.txt");
        Path link = root.resolve("escape2.txt");
        try {
            Files.writeString(secretFile, "original-secret-content");
            Files.createSymbolicLink(link, secretFile);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symlink creation is not permitted in this environment: " + e);
            return;
        }

        WriteFileTool tool = new WriteFileTool(root);
        ToolResult result = tool.execute(Map.of("path", "escape2.txt", "content", "TAMPERED", "append", true));

        assertFalse(result.isSuccess(), "appending through a symlink escaping every allowed root must be refused");
        try {
            assertEquals("original-secret-content", Files.readString(secretFile),
                    "the file outside the allowed roots must be untouched");
        } catch (IOException e) {
            fail(e);
        }
    }

    // ---- New-file-through-a-directory-link regression coverage (the reported symlink-escape gap) ----

    @Test
    public void creatingANewFileThroughADirectoryLinkEscapingEveryRootIsRejected(
            @TempDir Path root, @TempDir Path outside) throws IOException {
        Path link = root.resolve("linkdir");
        if (!createDirectoryLink(link, outside)) {
            Assumptions.abort("directory symlink/junction creation is not permitted in this environment");
            return;
        }

        WriteFileTool tool = new WriteFileTool(root);
        // "newfile.txt" does not exist anywhere yet — this is the "create a new file" path that
        // the pre-fix code skipped real-path containment for entirely.
        ToolResult result = tool.execute(Map.of("path", "linkdir/newfile.txt", "content", "TAMPERED"));

        assertFalse(result.isSuccess(),
                "creating a new file through a directory link escaping every allowed root must be refused: "
                        + (result.isSuccess() ? "" : result.getError()));
        assertFalse(Files.exists(outside.resolve("newfile.txt")),
                "the new file must not have been created outside the allowed roots");
    }

    @Test
    public void creatingANewFileNestedSeveralLevelsBelowADirectoryLinkEscapingEveryRootIsRejected(
            @TempDir Path root, @TempDir Path outside) throws IOException {
        Path link = root.resolve("linkdir");
        if (!createDirectoryLink(link, outside)) {
            Assumptions.abort("directory symlink/junction creation is not permitted in this environment");
            return;
        }

        WriteFileTool tool = new WriteFileTool(root);
        // Neither "sub1" nor "sub2" exists yet (inside the link target or anywhere else) —
        // exercises walking up MULTIPLE nonexistent levels before reaching the link itself.
        ToolResult result = tool.execute(Map.of("path", "linkdir/sub1/sub2/newfile.txt", "content", "TAMPERED"));

        assertFalse(result.isSuccess(),
                "creating a nested new file through a directory link escaping every allowed root must be refused: "
                        + (result.isSuccess() ? "" : result.getError()));
        assertFalse(Files.exists(outside.resolve("sub1")),
                "no directories must have been created outside the allowed roots");
    }

    @Test
    public void creatingANewFileSeveralNonexistentLevelsDeepInsideARootStillWorks(@TempDir Path root)
            throws IOException {
        // Control for the two tests above: ordinary nested-new-file creation, with no
        // symlink/junction anywhere on the path, must be completely unaffected by the fix.
        WriteFileTool tool = new WriteFileTool(root);

        ToolResult result = tool.execute(Map.of("path", "a/b/c/new.txt", "content", "hello"));

        assertTrue(result.isSuccess(), "expected success: " + result.getError());
        assertEquals("hello",
                Files.readString(root.resolve("a").resolve("b").resolve("c").resolve("new.txt"),
                        StandardCharsets.UTF_8));
    }

    /// Best-effort directory link creation for tests: tries a real symlink first (works on
    /// Linux/macOS with normal user rights; on Windows requires Developer Mode or elevation), then
    /// falls back to a Windows directory junction via `mklink /J`, which — unlike a symlink —
    /// needs NO elevation or Developer Mode on Windows, matching the exact primitive described in
    /// the vulnerability report. Returns false if neither succeeds, so the caller can skip via
    /// {@link Assumptions#abort}.
    private static boolean createDirectoryLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            // fall through to the junction attempt below
        }
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return false;
        }
        try {
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start();
            int exit = process.waitFor();
            return exit == 0 && Files.exists(link);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
