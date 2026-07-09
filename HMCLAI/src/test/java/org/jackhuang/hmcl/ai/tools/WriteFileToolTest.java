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
/// NOTE: a target that does not exist yet has nothing to resolve, so it is created directly inside
/// the allowed roots as before. When the target already exists (the common overwrite/append case),
/// a toRealPath()-based symlink-containment recheck now applies, mirroring EditTool's fix — see
/// {@link #overwritingThroughASymlinkEscapingEveryRootIsRejected}.
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
}
