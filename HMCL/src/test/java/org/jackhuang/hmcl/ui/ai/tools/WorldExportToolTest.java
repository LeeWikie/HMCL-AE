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
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.World;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [WorldExportTool]'s dispatch/validation contract and both of its real branches (export /
/// duplicate), driven through the real [org.jackhuang.hmcl.game.World] over a temp `saves/`
/// directory ([ProfileFixture]): the metadata/description contract, the action-dispatch guard (runs
/// before any {@link org.jackhuang.hmcl.setting.Profiles} access), the missing-`world` and
/// missing/invalid-`newName` guards, the "world not found" candidate-list envelope, the
/// "not a valid Minecraft world" translation of a corrupt `level.dat`, the never-overwrite contract
/// for both operations, and — since (unlike most of this tool suite) neither operation needs a
/// network call or real mod metadata — the actual export/duplicate success paths themselves.
///
/// The only thing intentionally left out is exercising these paths against a save produced by a
/// real, running Minecraft client (actual chunk/region data, a `LastPlayed`-bearing `level.dat`
/// shaped exactly like the target game version writes it, a `session.lock` genuinely held by a
/// separate game process). Here, `level.dat` is a minimal REAL gzip-compressed NBT file — built with
/// the same [org.glavo.nbt] APIs [World] itself reads, mirroring [NbtFixtures]'s
/// real-NBT-over-mocking approach — carrying just the two fields [World]'s constructor requires
/// (`Data.LevelName` / `Data.LastPlayed`); the "world is locked" branch is exercised with an
/// in-process [FileLock] on `session.lock`, relying on the documented same-JVM
/// `OverlappingFileLockException` behaviour of {@link FileChannel#tryLock()} (the exact mechanism
/// [World#isLocked()] itself special-cases) rather than an actual second game process. Those gaps
/// are left to the manual test checklist.
public final class WorldExportToolTest {

    private final WorldExportTool tool = new WorldExportTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("world_export", tool.getName());
        String description = tool.getDescription();
        assertTrue(description.contains("newName"),
                "must document the duplicate-only 'newName' parameter: " + description);
        assertTrue(description.contains(".zip"),
                "must document the .zip archive contract: " + description);
        assertTrue(description.contains("saves/"),
                "must document the saves/<world> contract: " + description);
        assertTrue(description.toLowerCase(Locale.ROOT).contains("never overwrite"),
                "must document the non-destructive contract: " + description);
    }

    @Test
    void unrecognizedActionIsRejectedWithoutTouchingAnyProfile() {
        // 'action' is validated first, before any profile/repository access, so this needs no
        // fixture (mirrors RollbackModToolTest's missing-'mod' guard, but for THIS tool the
        // pre-profile guard is 'action', since 'world' is only resolved after the instance is).
        ToolResult blank = tool.execute(Map.of());
        assertFalse(blank.isSuccess(), "a call with no recognizable 'action' must fail");
        assertTrue(blank.getError().toLowerCase(Locale.ROOT).contains("action"),
                "the failure must name the unrecognized 'action': " + blank.getError());

        ToolResult garbage = tool.execute(Map.of("action", "frobnicate"));
        assertFalse(garbage.isSuccess());
        assertTrue(garbage.getError().contains("frobnicate"),
                "the failure must echo back the unrecognized action: " + garbage.getError());
    }

    @Test
    void missingWorldParameterFailsAfterInstanceIsResolved() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "action", "worlds_export"));

            assertFalse(result.isSuccess(), "a call with no 'world' must fail");
            assertTrue(result.getError().contains("world"),
                    "the failure must name the missing 'world' parameter: " + result.getError());
        }
    }

    @Test
    void nonexistentWorldFailsWithCandidateEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path saves = fx.repository().getRunDirectory("Existing").resolve("saves");
            Files.createDirectories(saves.resolve("RealWorldA"));
            Files.createDirectories(saves.resolve("RealWorldB"));

            ToolResult result = tool.execute(Map.of(
                    "instance", "Existing", "action", "worlds_export", "world", "NoSuchWorld"));

            assertFalse(result.isSuccess());
            String err = result.getError();
            assertTrue(ToolFailures.isWellFormedEnvelope(err), "not a well-formed envelope: " + err);
            assertTrue(err.contains("was not found"), err);
            assertTrue(err.contains("RealWorldA") && err.contains("RealWorldB"),
                    "must list the real world names: " + err);
        }
    }

    @Test
    void invalidWorldDataIsRejected() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("Corrupted");
            Files.createDirectories(worldDir);
            // A level.dat that exists but is not gzip/NBT data at all: World's own constructor must
            // reject it, and the tool must translate that into a clear failure instead of letting a
            // raw parser exception escape.
            Files.writeString(worldDir.resolve("level.dat"), "this is not a valid level.dat file");

            ToolResult result = tool.execute(Map.of(
                    "instance", "Existing", "action", "worlds_export", "world", "Corrupted"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("could not be read as a valid Minecraft world"),
                    "unexpected message: " + result.getError());
        }
    }

    @Test
    void exportZipsTheWorldUsingItsInGameLevelName() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld");
            writeMinimalLevelDat(worldDir, "My Cool World");
            Files.writeString(worldDir.resolve("extra.txt"), "some world data");

            ToolResult result = tool.execute(Map.of(
                    "instance", "Existing", "action", "worlds_export", "world", "MyWorld"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("My Cool World"),
                    "must report the in-game LevelName as the zip's top-level folder: " + result.getOutput());

            Path expectedZip = fx.repository().getRunDirectory("Existing").resolve("MyWorld.zip");
            assertTrue(Files.exists(expectedZip), "expected the default target " + expectedZip + " to be created");

            try (ZipFile zip = new ZipFile(expectedZip.toFile())) {
                assertNotNull(zip.getEntry("My Cool World/level.dat"),
                        "the archive's top-level folder must be the world's in-game name, not the save-folder name");
                assertNotNull(zip.getEntry("My Cool World/extra.txt"));
            }
        }
    }

    @Test
    void exportRefusesToOverwriteAnExistingTargetFile() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld");
            writeMinimalLevelDat(worldDir, "My Cool World");
            Path preExisting = fx.repository().getRunDirectory("Existing").resolve("MyWorld.zip");
            Files.writeString(preExisting, "leftover-from-a-previous-export");

            ToolResult result = tool.execute(Map.of(
                    "instance", "Existing", "action", "worlds_export", "world", "MyWorld"));

            assertFalse(result.isSuccess(), "must never overwrite an existing target file");
            assertTrue(result.getError().contains("already exists"), "unexpected message: " + result.getError());
            assertEquals("leftover-from-a-previous-export", Files.readString(preExisting),
                    "the pre-existing file must be left untouched");
        }
    }

    @Test
    void duplicateMissingNewNameFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld");
            writeMinimalLevelDat(worldDir, "My Cool World");

            ToolResult result = tool.execute(Map.of(
                    "instance", "Existing", "action", "worlds_duplicate", "world", "MyWorld"));

            assertFalse(result.isSuccess(), "a duplicate call with no 'newName' must fail");
            assertTrue(result.getError().contains("newName"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void duplicateRejectsAnInvalidNewName() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld");
            writeMinimalLevelDat(worldDir, "My Cool World");

            ToolResult result = tool.execute(Map.of(
                    "instance", "Existing", "action", "worlds_duplicate",
                    "world", "MyWorld", "newName", ".."));

            assertFalse(result.isSuccess(), "'..' must be rejected as a folder name");
            assertTrue(result.getError().contains("not a valid folder name"),
                    "unexpected message: " + result.getError());
        }
    }

    @Test
    void duplicateRefusesExistingDestinationFolder() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path saves = fx.repository().getRunDirectory("Existing").resolve("saves");
            Path worldDir = saves.resolve("MyWorld");
            writeMinimalLevelDat(worldDir, "My Cool World");
            Files.createDirectories(saves.resolve("AlreadyThere"));

            ToolResult result = tool.execute(Map.of(
                    "instance", "Existing", "action", "worlds_duplicate",
                    "world", "MyWorld", "newName", "AlreadyThere"));

            assertFalse(result.isSuccess(), "must never overwrite an existing destination folder");
            assertTrue(result.getError().contains("already exists"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void duplicateCopiesWorldExcludesSessionLockAndRenamesLevelName() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path saves = fx.repository().getRunDirectory("Existing").resolve("saves");
            Path worldDir = saves.resolve("MyWorld");
            writeMinimalLevelDat(worldDir, "My Cool World");
            // A stray, unheld session.lock: World#copy must exclude it from the copy regardless of
            // whether it currently represents a live lock.
            Files.writeString(worldDir.resolve("session.lock"), "stray");

            ToolResult result = tool.execute(Map.of(
                    "instance", "Existing", "action", "worlds_duplicate",
                    "world", "MyWorld", "newName", "MyWorldCopy"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("MyWorldCopy"), "unexpected message: " + result.getOutput());

            Path newWorldDir = saves.resolve("MyWorldCopy");
            assertTrue(Files.isDirectory(newWorldDir));
            assertTrue(Files.exists(newWorldDir.resolve("level.dat")));
            assertFalse(Files.exists(newWorldDir.resolve("session.lock")),
                    "World#copy must exclude session.lock from the copy");

            World copy = new World(newWorldDir);
            assertEquals("MyWorldCopy", copy.getWorldName(),
                    "the copy's in-game LevelName must be set to the new folder name");

            // The original must be left completely untouched.
            World original = new World(worldDir);
            assertEquals("My Cool World", original.getWorldName());
        }
    }

    @Test
    void duplicateRefusesALockedWorld() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path saves = fx.repository().getRunDirectory("Existing").resolve("saves");
            Path worldDir = saves.resolve("MyWorld");
            writeMinimalLevelDat(worldDir, "My Cool World");
            Path lockFile = worldDir.resolve("session.lock");

            // Holds an in-process lock on session.lock for the duration of the call, mirroring what
            // World#lock() does for a real running game. World#isLocked() opens a SECOND FileChannel
            // on the same file and calls tryLock(); per FileChannel's documented same-JVM contract
            // this throws OverlappingFileLockException, which World#isLocked() translates to "locked".
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                assertNotNull(lock, "test setup failed to acquire the lock it needs to hold");

                ToolResult result = tool.execute(Map.of(
                        "instance", "Existing", "action", "worlds_duplicate",
                        "world", "MyWorld", "newName", "MyWorldCopy"));

                assertFalse(result.isSuccess(), "a world locked by a running game must not be duplicated");
                assertTrue(result.getError().contains("session.lock"), "unexpected message: " + result.getError());
            }

            assertFalse(Files.isDirectory(saves.resolve("MyWorldCopy")),
                    "no partial copy must be left behind after a refused duplicate");
        }
    }

    /// Writes a minimal, REAL (gzip-compressed) `level.dat` under {@code worldDir} — just enough for
    /// [World]'s constructor to accept the folder as a valid Minecraft world ({@code Data.LevelName}
    /// + {@code Data.LastPlayed}, the two fields [World] itself requires) — mirroring
    /// [NbtFixtures]'s real-NBT-over-mocking approach for the world/save-domain tools.
    private static void writeMinimalLevelDat(Path worldDir, String levelName) throws Exception {
        Files.createDirectories(worldDir);

        CompoundTag data = new CompoundTag();
        data.addString("LevelName", levelName);
        data.addLong("LastPlayed", 0L);
        CompoundTag root = new CompoundTag();
        root.addTag("Data", data);

        Path levelDat = worldDir.resolve("level.dat");
        try (OutputStream out = Files.newOutputStream(levelDat);
             OutputStream gzip = new GZIPOutputStream(out)) {
            NBTCodec.of().writeTag(gzip, root);
        }
    }
}
