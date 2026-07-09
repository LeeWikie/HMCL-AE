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
package org.jackhuang.hmcl.ai.tools;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Regression coverage for the multi-root resolution bug: a relative `path` given to `read` used
/// to be resolved ONLY against `roots.get(0)`, so a path that only existed under a root added later
/// via [`FileReadTool#addRoot`] (e.g. the currently-selected game/instance directory) failed with
/// "Path does not exist", even though it was reachable under an allowed root.
public final class FileReadToolTest {

    /// The exact reported bug: `.minecraft/versions` only exists under the second root (the game
    /// directory), not under the primary root (the launcher config dir). It must still resolve.
    @Test
    void relativePathOnlyUnderSecondRootResolvesAsDirectory(@TempDir Path primaryRoot, @TempDir Path secondRoot) throws IOException {
        Path versions = secondRoot.resolve(".minecraft").resolve("versions");
        Files.createDirectories(versions.resolve("1.20.1"));

        FileReadTool tool = new FileReadTool(primaryRoot);
        tool.addRoot(secondRoot);

        ToolResult result = tool.execute(Map.of("path", ".minecraft/versions"));

        assertTrue(result.isSuccess(), "should resolve against the second root: " + result.getError());
        assertTrue(result.getOutput().contains("1.20.1"), "should list the version folder");
    }

    /// Same bug, but for a plain file read rather than a directory listing.
    @Test
    void relativePathOnlyUnderSecondRootResolvesAsFile(@TempDir Path primaryRoot, @TempDir Path secondRoot) throws IOException {
        Path logFile = secondRoot.resolve("logs").resolve("latest.log");
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, "hello from the game directory");

        FileReadTool tool = new FileReadTool(primaryRoot);
        tool.addRoot(secondRoot);

        ToolResult result = tool.execute(Map.of("path", "logs/latest.log"));

        assertTrue(result.isSuccess(), "should resolve against the second root: " + result.getError());
        assertEquals("hello from the game directory", result.getOutput());
    }

    /// When the same relative path exists under both roots, today's priority (primary root first)
    /// must be preserved.
    @Test
    void pathExistingUnderBothRootsPrefersPrimaryRoot(@TempDir Path primaryRoot, @TempDir Path secondRoot) throws IOException {
        Files.writeString(primaryRoot.resolve("shared.txt"), "from primary root");
        Files.writeString(secondRoot.resolve("shared.txt"), "from second root");

        FileReadTool tool = new FileReadTool(primaryRoot);
        tool.addRoot(secondRoot);

        ToolResult result = tool.execute(Map.of("path", "shared.txt"));

        assertTrue(result.isSuccess());
        assertEquals("from primary root", result.getOutput());
    }

    /// A relative path that exists under neither root must still fail, with a message that makes
    /// clear every root was tried (not just the primary one).
    @Test
    void pathNotExistingUnderEitherRootFailsWithClearMessage(@TempDir Path primaryRoot, @TempDir Path secondRoot) {
        FileReadTool tool = new FileReadTool(primaryRoot);
        tool.addRoot(secondRoot);

        ToolResult result = tool.execute(Map.of("path", "nowhere/nothing.txt"));

        assertFalse(result.isSuccess());
        String error = result.getError();
        assertNotNull(error);
        assertTrue(error.contains(primaryRoot.toAbsolutePath().normalize().toString()), "should mention the primary root: " + error);
        assertTrue(error.contains(secondRoot.toAbsolutePath().normalize().toString()), "should mention the second root: " + error);
    }

    /// Directory listing with only a single root (no `addRoot` call) must behave exactly as before.
    @Test
    void singleRootDirectoryListingUnchanged(@TempDir Path primaryRoot) throws IOException {
        Files.createDirectories(primaryRoot.resolve("sub"));
        Files.writeString(primaryRoot.resolve("sub").resolve("file.txt"), "content");

        FileReadTool tool = new FileReadTool(primaryRoot);

        ToolResult result = tool.execute(Map.of("path", "sub"));

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("file.txt"));
    }

    /// Reading a file with only a single root (no `addRoot` call) must behave exactly as before.
    @Test
    void singleRootFileReadingUnchanged(@TempDir Path primaryRoot) throws IOException {
        Files.writeString(primaryRoot.resolve("readme.txt"), "line one\nline two");

        FileReadTool tool = new FileReadTool(primaryRoot);

        ToolResult result = tool.execute(Map.of("path", "readme.txt"));

        assertTrue(result.isSuccess());
        assertEquals("line one\nline two", result.getOutput());
    }

    /// A single-root lookup for a path that does not exist anywhere must still fail with a
    /// path-does-not-exist style message (there is nothing else to "try").
    @Test
    void singleRootMissingPathStillFails(@TempDir Path primaryRoot) {
        FileReadTool tool = new FileReadTool(primaryRoot);

        ToolResult result = tool.execute(Map.of("path", "missing.txt"));

        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
    }

    /// An absolute path that resolves outside every allowed root must be rejected.
    @Test
    void absolutePathOutsideAllowedRootsIsRejected(@TempDir Path primaryRoot, @TempDir Path outside) throws IOException {
        Files.writeString(outside.resolve("secret.txt"), "top secret");
        FileReadTool tool = new FileReadTool(primaryRoot);

        ToolResult result = tool.execute(Map.of("path", outside.resolve("secret.txt").toString()));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("outside the allowed roots"));
    }

    /// A relative "../" traversal that walks past the root must be rejected, not silently resolved
    /// to whatever lies above the root.
    @Test
    void relativeTraversalOutsideRootIsRejected(@TempDir Path primaryRoot) throws IOException {
        Path sub = primaryRoot.resolve("sub").resolve("dir");
        Files.createDirectories(sub);
        Files.writeString(primaryRoot.resolve("sibling.txt"), "not meant to be read this way");
        FileReadTool tool = new FileReadTool(sub);

        ToolResult result = tool.execute(Map.of("path", "../../../sibling.txt"));

        assertFalse(result.isSuccess(), "a '..' traversal walking past the root must be rejected");
    }

    /// A symlink planted INSIDE an allowed root but pointing OUTSIDE it must not be readable
    /// through the `read` tool — Files.exists()/Files.isDirectory() etc. all follow symlinks, so
    /// the lexical/normalized containment check alone is not enough; the REAL (symlink-resolved)
    /// path must also be re-verified to stay within an allowed root.
    @Test
    void symlinkInsideRootPointingOutsideCannotBeRead(@TempDir Path root, @TempDir Path outside) {
        Path secret = outside.resolve("secret.txt");
        Path link = root.resolve("escape.txt");
        try {
            Files.writeString(secret, "leaked-content");
            Files.createSymbolicLink(link, secret);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symlink creation is not permitted in this environment: " + e);
            return;
        }

        FileReadTool tool = new FileReadTool(root);
        ToolResult result = tool.execute(Map.of("path", "escape.txt"));

        assertFalse(result.isSuccess(), "reading through a symlink escaping every allowed root must be refused");
    }
}
