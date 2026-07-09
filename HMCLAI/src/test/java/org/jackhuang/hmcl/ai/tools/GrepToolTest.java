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
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Regression guard for a real diagnostic trace: `grep` called with `path` pointing at a
/// FILE (e.g. `.../launcher-settings.json`) used to fail with a confusing "Not a directory"
/// error. Locks in single-file search support, the new "does not exist" message, unchanged
/// directory-search behavior, and the allowed-roots guard.
public final class GrepToolTest {

    @Test
    void matchesInsideASingleNamedFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("launcher-settings.json");
        Files.writeString(file, "line one\n\"key\": \"value\"\nline three\n");

        GrepTool tool = new GrepTool(dir);
        ToolResult result = tool.execute(Map.of("pattern", "key", "path", file.toString()));

        assertTrue(result.isSuccess());
        assertEquals("launcher-settings.json:2: \"key\": \"value\"", result.getOutput());
    }

    @Test
    void nonexistentPathReturnsClearMessageNotNotADirectory(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist.json");

        GrepTool tool = new GrepTool(dir);
        ToolResult result = tool.execute(Map.of("pattern", "x", "path", missing.toString()));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Path does not exist"), result.getError());
        assertFalse(result.getError().contains("Not a directory"), result.getError());
    }

    @Test
    void directorySearchStillWorks(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "alpha needle beta\nno match here\n");
        Files.writeString(dir.resolve("b.txt"), "another needle here\n");
        Files.writeString(dir.resolve("c.txt"), "nothing to see\n");

        GrepTool tool = new GrepTool(dir);
        ToolResult result = tool.execute(Map.of("pattern", "needle"));

        assertTrue(result.isSuccess());
        String output = result.getOutput();
        assertTrue(output.contains("a.txt:1: alpha needle beta"), output);
        assertTrue(output.contains("b.txt:1: another needle here"), output);
        assertFalse(output.contains("c.txt"), output);
    }

    @Test
    void pathOutsideAllowedRootsIsRejected(@TempDir Path dir, @TempDir Path otherDir) {
        GrepTool tool = new GrepTool(dir);
        ToolResult result = tool.execute(Map.of("pattern", "x", "path", otherDir.toString()));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("outside the allowed roots"), result.getError());
    }

    /// A symlink planted INSIDE the allowed root but pointing to a file OUTSIDE it must not be
    /// searched — its matching lines would otherwise leak content from outside every allowed root.
    @Test
    void symlinkInsideRootPointingOutsideIsNotSearched(@TempDir Path dir, @TempDir Path outside) {
        Path secret = outside.resolve("secret.txt");
        Path link = dir.resolve("escape.txt");
        try {
            Files.writeString(secret, "needle-that-should-not-leak");
            Files.createSymbolicLink(link, secret);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symlink creation is not permitted in this environment: " + e);
            return;
        }

        GrepTool tool = new GrepTool(dir);
        ToolResult result = tool.execute(Map.of("pattern", "needle"));

        assertTrue(result.isSuccess());
        assertFalse(result.getOutput().contains("needle-that-should-not-leak"),
                "content reached only through a symlink escaping every allowed root must not be searched: "
                        + result.getOutput());
    }
}
