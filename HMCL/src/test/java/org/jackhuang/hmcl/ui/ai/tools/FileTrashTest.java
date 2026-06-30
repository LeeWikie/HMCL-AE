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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks in the permanent-delete path of FileTrash (preferTrash=false), which is deterministic
/// across platforms (the recycle-bin path depends on a desktop being available, so it is not
/// asserted here). Also verifies deleting a missing path is a no-op.
public final class FileTrashTest {

    @Test
    void permanentDeleteRemovesADirectoryTree(@TempDir Path tmp) throws IOException {
        Path world = tmp.resolve("world");
        Files.createDirectories(world.resolve("region"));
        Files.write(world.resolve("level.dat"), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(world.resolve("region/r.0.0.mca"), "y".getBytes(StandardCharsets.UTF_8));

        boolean trashed = FileTrash.delete(world, false); // preferTrash off → permanent
        assertFalse(trashed, "preferTrash=false must report a permanent delete (false)");
        assertFalse(Files.exists(world), "the directory tree should be gone");
    }

    @Test
    void deletingAMissingPathIsANoOp(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist");
        assertDoesNotThrow(() -> FileTrash.delete(missing, false));
    }

    @Test
    void trashSupportedDoesNotThrow() {
        assertDoesNotThrow(FileTrash::trashSupported);
    }
}
