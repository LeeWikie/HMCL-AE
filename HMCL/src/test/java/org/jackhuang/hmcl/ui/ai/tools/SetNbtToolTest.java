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

import org.glavo.nbt.io.NBTCodec;
import org.glavo.nbt.tag.CompoundTag;
import org.glavo.nbt.tag.ListTag;
import org.glavo.nbt.tag.TagType;
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [SetNbtTool]'s write-path safety contract with real gzip NBT files (no mocks,
/// mirroring [TransferInventoryToolTest]):
///   - a world whose `session.lock` is held (same-JVM `FileChannel#tryLock` holder, exactly
///     like [GameResourceGuardTest]) is refused with the unified envelope and the file is
///     never touched;
///   - an idle world is edited under a HELD session lock which is released afterwards, a
///     timestamped .bak backup is taken and the receipt reports the change;
///   - [NbtToolSupport#postWriteValidation] (the NBT leg of the post-write validation
///     contract) returns `null` for a clean read-back, and a WARNING for garbage bytes or a
///     root-tag-type mismatch.
public final class SetNbtToolTest {

    private final SetNbtTool tool = new SetNbtTool();

    /// Writes a minimal real (gzip) `level.dat`: `{Data: {XpLevel: <xp>}}`.
    private static Path writeLevelDat(Path worldDir, int xp) throws Exception {
        Files.createDirectories(worldDir);
        CompoundTag root = new CompoundTag();
        CompoundTag data = new CompoundTag();
        data.addInt("XpLevel", xp);
        root.addTag("Data", data);
        Path file = worldDir.resolve("level.dat");
        try (OutputStream out = Files.newOutputStream(file); OutputStream gzip = new GZIPOutputStream(out)) {
            NBTCodec.of().writeTag(gzip, root);
        }
        return file;
    }

    @Test
    void refusesWithEnvelopeWhileWorldIsLockedAndLeavesFileUntouched() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            Path worldDir = fx.repository().getRunDirectory("Inst").resolve("saves").resolve("MyWorld");
            Path levelDat = writeLevelDat(worldDir, 7);
            byte[] before = Files.readAllBytes(levelDat);

            try (FileChannel holder = FileChannel.open(worldDir.resolve("session.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock held = holder.tryLock();
                assertNotNull(held, "test setup: could not take the session lock");

                ToolResult result = tool.execute(Map.of("instance", "Inst",
                        "world", "MyWorld", "file", "level.dat",
                        "nbtPath", "Data.XpLevel", "value", "42"));

                assertFalse(result.isSuccess(), "a locked world must refuse the write");
                assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                        "not a well-formed envelope: " + result.getError());
                assertTrue(result.getError().contains("Retryable: later"),
                        "unexpected classification: " + result.getError());
                assertTrue(result.getError().contains("MyWorld"),
                        "should name the world: " + result.getError());
            }

            assertArrayEquals(before, Files.readAllBytes(levelDat), "the refused write must not touch the file");
        }
    }

    @Test
    void editsScalarUnderHeldLockTakesBackupAndReleasesLock() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            Path worldDir = fx.repository().getRunDirectory("Inst").resolve("saves").resolve("MyWorld");
            Path levelDat = writeLevelDat(worldDir, 7);

            ToolResult result = tool.execute(Map.of("instance", "Inst",
                    "world", "MyWorld", "file", "level.dat",
                    "nbtPath", "Data.XpLevel", "value", "42"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("Updated Data.XpLevel"),
                    "unexpected receipt: " + result.getOutput());
            assertFalse(result.getOutput().contains("WARNING: post-write validation"),
                    "a clean write must not warn: " + result.getOutput());

            CompoundTag reread = NbtToolSupport.readCompound(levelDat);
            assertEquals(42, ((CompoundTag) reread.get("Data")).getIntOrNull("XpLevel"));

            long backups;
            try (var children = Files.list(worldDir)) {
                backups = children.filter(p -> p.getFileName().toString().startsWith("level.dat.bak-")).count();
            }
            assertEquals(1, backups, "exactly one pre-write backup must exist");

            // The session lock held during the write must be released afterwards.
            try (FileChannel probe = FileChannel.open(worldDir.resolve("session.lock"),
                    StandardOpenOption.WRITE)) {
                FileLock lock = probe.tryLock();
                assertNotNull(lock, "session.lock is still held after the write returned");
                lock.release();
            }
        }
    }

    /// T10: a path that misses reports the sibling keys of the deepest resolvable node (so the
    /// model can fix a typo without a full-tree dump) and does NOT write or back up anything.
    @Test
    void missingPathEnumeratesSiblingKeysAndWritesNothing() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            Path worldDir = fx.repository().getRunDirectory("Inst").resolve("saves").resolve("MyWorld");
            Path levelDat = writeLevelDat(worldDir, 7);
            byte[] before = Files.readAllBytes(levelDat);

            ToolResult result = tool.execute(Map.of("instance", "Inst",
                    "world", "MyWorld", "file", "level.dat",
                    "nbtPath", "Data.NoSuchKey", "value", "42"));

            assertFalse(result.isSuccess(), "editing a non-existent path must fail");
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(result.getError().contains("Retryable: yes"), result.getError());
            assertTrue(result.getError().contains("CompoundTag keys:"),
                    "must enumerate the keys that exist at the deepest node: " + result.getError());
            assertTrue(result.getError().contains("XpLevel"),
                    "must list the real sibling key: " + result.getError());

            assertArrayEquals(before, Files.readAllBytes(levelDat), "a missed path must not touch the file");
            try (var children = Files.list(worldDir)) {
                assertFalse(children.anyMatch(p -> p.getFileName().toString().startsWith("level.dat.bak-")),
                        "a missed path must not create a backup");
            }
        }
    }

    // ---------------------------------------------------------------------
    // NbtToolSupport.postWriteValidation (the NBT post-write validation leg)
    // ---------------------------------------------------------------------

    @Test
    void postWriteValidationPassesOnACleanWrite(@TempDir Path tempDir) throws Exception {
        Path file = writeLevelDat(tempDir.resolve("World"), 3);
        CompoundTag written = NbtToolSupport.readCompound(file);

        assertNull(NbtToolSupport.postWriteValidation(file, written));
    }

    @Test
    void postWriteValidationWarnsOnGarbageBytes(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("broken.dat");
        Files.writeString(file, "this is not NBT");

        String warning = NbtToolSupport.postWriteValidation(file, new CompoundTag());

        assertNotNull(warning, "garbage bytes must produce a warning");
        assertTrue(warning.startsWith("WARNING:"), "unexpected warning: " + warning);
        assertTrue(warning.contains("valid NBT"), "should explain the problem: " + warning);
        assertTrue(warning.contains(".bak"), "should point at the pre-write backup: " + warning);
    }

    @Test
    void postWriteValidationWarnsOnRootTagTypeMismatch(@TempDir Path tempDir) throws Exception {
        Path file = writeLevelDat(tempDir.resolve("World"), 3); // on disk: a COMPOUND root

        String warning = NbtToolSupport.postWriteValidation(file, new ListTag<>(TagType.COMPOUND));

        assertNotNull(warning, "a root tag type mismatch must produce a warning");
        assertTrue(warning.startsWith("WARNING:"), "unexpected warning: " + warning);
        assertTrue(warning.contains("TAG_Compound"), "should name the read-back type: " + warning);
        assertTrue(warning.contains("TAG_List"), "should name the written type: " + warning);
    }

}
