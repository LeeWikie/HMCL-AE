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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class FileBackupToolTest {

    private final FileBackupTool tool = new FileBackupTool();

    @Test
    void testBackupAndRestore(@TempDir Path tempDir) throws IOException {
        Path original = tempDir.resolve("test.txt");
        Files.writeString(original, "Hello, HMCL-AE!");

        // Backup
        ToolResult result = tool.execute(Map.of("action", "backup", "path", original.toString()));
        assertTrue(result.isSuccess(), result.getOutput());
        assertTrue(Files.exists(Path.of(original + ".ae-backup")));
        assertTrue(Files.exists(Path.of(original + ".ae-backup.sha256")));
        assertTrue(result.getOutput().contains("SHA-256"));

        // Modify original
        Files.writeString(original, "Modified content");

        // Restore
        result = tool.execute(Map.of("action", "restore", "path", original.toString()));
        assertTrue(result.isSuccess(), result.getError());
        assertEquals("Hello, HMCL-AE!", Files.readString(original));
    }

    @Test
    void testBackupMissingFile(@TempDir Path tempDir) {
        Path nonexistent = tempDir.resolve("nonexistent.txt");
        ToolResult result = tool.execute(Map.of("action", "backup", "path", nonexistent.toString()));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("does not exist"));
    }

    @Test
    void testRestoreNoBackup(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("no_backup.txt");
        Files.writeString(file, "content");
        ToolResult result = tool.execute(Map.of("action", "restore", "path", file.toString()));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("No backup found"));
    }

    @Test
    void testMissingParameters() {
        ToolResult result = tool.execute(Map.of());
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Missing required parameter"));
    }

    @Test
    void testUnknownAction() {
        ToolResult result = tool.execute(Map.of("action", "delete", "path", "/tmp/test"));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Unknown action"));
    }

    @Test
    void testToolMetadata() {
        assertEquals("file_backup", tool.getName());
        assertNotNull(tool.getDescription());
        assertTrue(tool.getDescription().contains("backup"));
    }
}
