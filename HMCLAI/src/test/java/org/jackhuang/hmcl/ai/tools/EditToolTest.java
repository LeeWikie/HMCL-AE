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

/// Covers EditTool's root-containment logic, including the symlink-escape fix: a symlink planted
/// INSIDE an allowed root but pointing to a file OUTSIDE it must never be editable through that
/// symlink — otherwise the lexical/normalized containment check alone lets a write escalate to an
/// arbitrary external file.
public final class EditToolTest {

    @Test
    public void editsFileInPlace(@TempDir Path root) throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "hello world");
        // The read-before-edit contract requires a recorded read; use an isolated ledger so
        // this test doesn't touch the global one.
        ReadLedger ledger = new ReadLedger();
        ledger.recordRead(file, Files.readAllBytes(file));
        EditTool tool = new EditTool(root, ledger);

        ToolResult result = tool.execute(Map.of("path", "a.txt", "old_string", "world", "new_string", "there"));

        assertTrue(result.isSuccess(), "expected success: " + result.getError());
        assertEquals("hello there", Files.readString(file));
    }

    @Test
    public void pathOutsideAllowedRootsIsRejected(@TempDir Path root, @TempDir Path outside) throws IOException {
        Path file = outside.resolve("a.txt");
        Files.writeString(file, "hello world");
        EditTool tool = new EditTool(root);

        ToolResult result = tool.execute(Map.of(
                "path", file.toString(), "old_string", "world", "new_string", "there"));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("outside the allowed roots"));
        assertEquals("hello world", Files.readString(file), "the outside file must be untouched");
    }

    @Test
    public void symlinkInsideRootPointingOutsideCannotBeEdited(@TempDir Path root, @TempDir Path outside) {
        Path secretFile = outside.resolve("secret.txt");
        Path link = root.resolve("escape.txt");
        try {
            Files.writeString(secretFile, "original-secret-content");
            Files.createSymbolicLink(link, secretFile);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symlink creation is not permitted in this environment: " + e);
            return;
        }

        EditTool tool = new EditTool(root);
        ToolResult result = tool.execute(Map.of(
                "path", "escape.txt", "old_string", "original-secret-content", "new_string", "TAMPERED"));

        assertFalse(result.isSuccess(), "editing through a symlink escaping every allowed root must be refused");
        try {
            assertEquals("original-secret-content", Files.readString(secretFile),
                    "the file outside the allowed roots must be untouched");
        } catch (IOException e) {
            fail(e);
        }
    }

    // ---- Mandatory backup-before-edit (hard precondition, every approval mode) ----

    @Test
    public void editSnapshotsPreEditContentToSiblingBak(@TempDir Path root) throws IOException {
        Path file = root.resolve("config.json");
        Files.writeString(file, "{\"fov\":70}");
        ReadLedger ledger = new ReadLedger();
        ledger.recordRead(file, Files.readAllBytes(file));
        EditTool tool = new EditTool(root, ledger);

        ToolResult result = tool.execute(Map.of("path", "config.json", "old_string", "70", "new_string", "90"));

        assertTrue(result.isSuccess(), "expected success: " + result.getError());
        assertEquals("{\"fov\":90}", Files.readString(file), "the edit must land");
        Path bak = root.resolve("config.json.bak");
        assertTrue(Files.exists(bak), "a mandatory .bak snapshot must exist after the edit");
        assertEquals("{\"fov\":70}", Files.readString(bak), "the .bak must hold the PRE-edit content");
    }

    @Test
    public void editIsRefusedAndChangesNothingWhenBackupCannotBeWritten(@TempDir Path root) throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "hello world");
        ReadLedger ledger = new ReadLedger();
        ledger.recordRead(file, Files.readAllBytes(file));
        // A non-empty directory occupying the exact .bak destination makes the snapshot fail-closed
        // (DirectoryNotEmptyException), so the hard precondition can never be satisfied here.
        Path blocker = root.resolve("a.txt.bak");
        Files.createDirectory(blocker);
        Files.writeString(blocker.resolve("keep"), "x");
        EditTool tool = new EditTool(root, ledger);

        ToolResult result = tool.execute(Map.of("path", "a.txt", "old_string", "world", "new_string", "there"));

        assertFalse(result.isSuccess(), "with no restore point the edit must be refused (hard precondition)");
        assertEquals("hello world", Files.readString(file), "a refused edit must not modify the file");
    }
}
