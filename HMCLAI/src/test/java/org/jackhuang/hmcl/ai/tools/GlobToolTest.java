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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Covers GlobTool's root-containment logic, including the symlink-escape fix: a symlink planted
/// INSIDE an allowed root but pointing OUTSIDE it must never be reported as a matching path
/// (Files::isRegularFile follows symlinks, so the lexical/normalized containment check alone is
/// not enough).
public final class GlobToolTest {

    @Test
    public void findsMatchingFilesUnderRoot(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("a.json"), "{}");
        Files.writeString(root.resolve("b.txt"), "text");
        GlobTool tool = new GlobTool(root);

        ToolResult result = tool.execute(Map.of("pattern", "*.json"));

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("a.json"));
        assertFalse(result.getOutput().contains("b.txt"));
    }

    @Test
    public void pathOutsideAllowedRootsIsRejected(@TempDir Path root, @TempDir Path outside) throws IOException {
        GlobTool tool = new GlobTool(root);

        ToolResult result = tool.execute(Map.of("pattern", "*.txt", "path", outside.toString()));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("outside the allowed roots"));
    }

    @Test
    public void symlinkInsideRootPointingOutsideIsNotReported(@TempDir Path root, @TempDir Path outside) {
        Path secret = outside.resolve("secret.txt");
        Path link = root.resolve("escape.txt");
        try {
            Files.writeString(secret, "leaked-content");
            Files.createSymbolicLink(link, secret);
        } catch (IOException | UnsupportedOperationException e) {
            // Symlink creation may be refused (e.g. Windows without Developer Mode/admin) —
            // the escape scenario simply can't be exercised in that environment.
            Assumptions.abort("symlink creation is not permitted in this environment: " + e);
            return;
        }

        GlobTool tool = new GlobTool(root);
        ToolResult result = tool.execute(Map.of("pattern", "*.txt"));

        assertTrue(result.isSuccess());
        assertFalse(result.getOutput().contains("escape.txt"),
                "a symlink pointing outside every allowed root must not be reported as a match: " + result.getOutput());
    }
}
