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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Unit coverage for {@link FileBackup}: it snapshots a small file to a sibling `.bak`, is
/// idempotent for identical content, refuses over-threshold files, fails-closed on an unwritable
/// destination, and re-asserts root containment.
public final class FileBackupTest {

    private static BackupTargetResolver.Target target(Path file, Path root) {
        return new BackupTargetResolver.Target(file, List.of(root));
    }

    @Test
    void snapshotsSmallFileToSiblingBak(@TempDir Path root) throws IOException {
        Path file = root.resolve("options.txt");
        Files.writeString(file, "fov:70");

        FileBackup.Result result = FileBackup.backup(target(file, root), FileBackup.DEFAULT_MAX_BYTES);

        assertTrue(result.success(), "small file should back up: " + result.reason());
        // Compare by filename, not the whole path: toRealPath() may canonicalize the temp dir
        // (Windows 8.3 short names / case) so the backup path won't string-equal root.resolve(...).
        assertTrue(result.backupPath().endsWith("options.txt.bak"), "wrong backup name: " + result.backupPath());
        assertArrayEquals("fov:70".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(result.backupPath()));
    }

    @Test
    void isIdempotentWhenBackupAlreadyIdentical(@TempDir Path root) throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "same");
        FileBackup.Result first = FileBackup.backup(target(file, root), FileBackup.DEFAULT_MAX_BYTES);
        assertTrue(first.success());

        FileBackup.Result second = FileBackup.backup(target(file, root), FileBackup.DEFAULT_MAX_BYTES);
        assertTrue(second.success(), "a second identical backup is a no-op success");
        assertEquals(first.backupPath(), second.backupPath(), "no fan-out of copies");
        // Exactly one .bak sibling, no numbered spillover.
        assertFalse(Files.exists(root.resolve("a.txt.1.bak")));
    }

    @Test
    void refusesFileOverThreshold(@TempDir Path root) throws IOException {
        Path file = root.resolve("big.bin");
        Files.write(file, new byte[2048]);

        FileBackup.Result result = FileBackup.backup(target(file, root), 1024);

        assertFalse(result.success());
        assertTrue(result.tooLarge(), "over-threshold must be reported as tooLarge, not a generic failure");
        assertFalse(Files.exists(root.resolve("big.bin.bak")), "no snapshot for an over-threshold file");
    }

    @Test
    void failsClosedWhenDestinationBlockedByDirectory(@TempDir Path root) throws IOException {
        Path file = root.resolve("a.txt");
        Files.writeString(file, "content");
        // A non-empty directory occupying the exact .bak destination makes the copy throw
        // (DirectoryNotEmptyException) deterministically on every platform — the reliable
        // "backup failed" injection the adapter test also relies on.
        Path blocker = root.resolve("a.txt.bak");
        Files.createDirectory(blocker);
        Files.writeString(blocker.resolve("keep"), "x");

        FileBackup.Result result = FileBackup.backup(target(file, root), FileBackup.DEFAULT_MAX_BYTES);

        assertFalse(result.success(), "a blocked destination must fail");
        assertFalse(result.tooLarge());
    }

    @Test
    void refusesTargetOutsideAllowedRoots(@TempDir Path root, @TempDir Path outside) throws IOException {
        Path file = outside.resolve("secret.txt");
        Files.writeString(file, "secret");

        FileBackup.Result result = FileBackup.backup(
                new BackupTargetResolver.Target(file, List.of(root)), FileBackup.DEFAULT_MAX_BYTES);

        assertFalse(result.success(), "a target outside the allowed roots must be refused");
        assertFalse(Files.exists(outside.resolve("secret.txt.bak")));
    }
}
