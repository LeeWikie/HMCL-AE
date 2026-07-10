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

import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Concurrency-guard and atomicity coverage for [WorldBackupManager] against a real
/// [ProfileFixture]-backed instance on disk (no mocks):
///   - restore() is REFUSED up front (unified envelope) while the world's `session.lock` is
///     held by a running game — a same-JVM `FileChannel#tryLock` holder simulates the game,
///     exactly like [GameResourceGuardTest];
///   - createBackup() holds the session lock for the copy (a busy world flips the
///     `lockedDuringBackup` soft flag instead of failing) and NEVER copies `session.lock`
///     into the snapshot (the native `WorldBackupTask` exclusion);
///   - an unknown backupId yields an envelope carrying the most recent REAL snapshot ids;
///   - the mid-swap `.restore-in-progress` marker exists exactly while `saves/<world>` is
///     absent (simulated crash via the package-private test hook) and
///     [WorldBackupManager#scanInterruptedRestores] finds every leftover.
public final class WorldBackupManagerTest {

    @AfterEach
    void clearCrashHook() {
        WorldBackupManager.crashBetweenMovesForTesting = null;
    }

    private static Path makeWorld(ProfileFixture fx, String instance, String world, String levelData)
            throws IOException {
        Path worldDir = fx.repository().getRunDirectory(instance).resolve("saves").resolve(world);
        Files.createDirectories(worldDir);
        Files.writeString(worldDir.resolve("level.dat"), levelData);
        return worldDir;
    }

    /// Opens (creating if needed) and `tryLock`s the world's `session.lock`, simulating a
    /// running game session. Close the returned channel to "quit the game".
    private static FileChannel holdSessionLock(Path worldDir) throws IOException {
        FileChannel holder = FileChannel.open(worldDir.resolve("session.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = holder.tryLock();
        assertNotNull(lock, "test setup: could not take the session lock");
        return holder;
    }

    // ---------------------------------------------------------------------
    // G3: restore() explicit lock pre-check
    // ---------------------------------------------------------------------

    @Test
    void restoreIsRefusedUpFrontWhileWorldIsLocked() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            Path worldDir = makeWorld(fx, "Inst", "MyWorld", "original");
            String backupId = WorldBackupManager.createBackup("Inst", "MyWorld", 0).id();

            try (FileChannel ignored = holdSessionLock(worldDir)) {
                IOException refused = assertThrows(IOException.class,
                        () -> WorldBackupManager.restore("Inst", "MyWorld", backupId, 0));

                assertTrue(ToolFailures.isWellFormedEnvelope(refused.getMessage()),
                        "not a well-formed envelope: " + refused.getMessage());
                assertTrue(refused.getMessage().contains("Retryable: later"),
                        "unexpected classification: " + refused.getMessage());
                assertTrue(refused.getMessage().contains("MyWorld"),
                        "should name the world: " + refused.getMessage());
            }

            // The refusal must happen BEFORE any file operation: live world untouched, no
            // staging/marker/replaced residue anywhere.
            assertEquals("original", Files.readString(worldDir.resolve("level.dat")));
            assertTrue(WorldBackupManager.scanInterruptedRestores("Inst").isEmpty(),
                    "a refused restore must leave no leftovers");
        }
    }

    // ---------------------------------------------------------------------
    // G4 + G11: createBackup() under the session lock, session.lock excluded
    // ---------------------------------------------------------------------

    @Test
    void createBackupExcludesSessionLockFromTheSnapshot() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            Path worldDir = makeWorld(fx, "Inst", "MyWorld", "data");
            Files.writeString(worldDir.resolve("session.lock"), "☃"); // present but NOT held

            WorldBackupManager.BackupResult result = WorldBackupManager.createBackup("Inst", "MyWorld", 0);

            assertFalse(result.lockedDuringBackup(), "an unheld session.lock is not a busy world");
            assertTrue(Files.isRegularFile(result.backupPath().resolve("level.dat")));
            assertFalse(Files.exists(result.backupPath().resolve("session.lock")),
                    "session.lock must never be copied into a snapshot");
            assertEquals(1, result.fileCount(), "only level.dat should have been counted");
        }
    }

    @Test
    void createBackupOnBusyWorldStillSucceedsButFlagsInconsistencyRisk() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            Path worldDir = makeWorld(fx, "Inst", "MyWorld", "data");

            try (FileChannel ignored = holdSessionLock(worldDir)) {
                WorldBackupManager.BackupResult result = WorldBackupManager.createBackup("Inst", "MyWorld", 0);

                // The lock could not be acquired (the "game" holds it), so the copy proceeded
                // unlocked and the soft flag must be raised — proving withWorldLock IS on the
                // path (the flag is only ever set via its WorldBusyException).
                assertTrue(result.lockedDuringBackup(),
                        "a busy world must flag the snapshot as possibly inconsistent");
                assertTrue(Files.isRegularFile(result.backupPath().resolve("level.dat")));
                assertFalse(Files.exists(result.backupPath().resolve("session.lock")));
            }
        }
    }

    @Test
    void createBackupOnIdleWorldReleasesTheSessionLockAfterwards() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            Path worldDir = makeWorld(fx, "Inst", "MyWorld", "data");

            WorldBackupManager.createBackup("Inst", "MyWorld", 0);

            // withWorldLock stamped and held session.lock during the copy; afterwards it must
            // be fully released — a fresh tryLock must succeed.
            try (FileChannel probe = FileChannel.open(worldDir.resolve("session.lock"),
                    StandardOpenOption.WRITE)) {
                FileLock lock = probe.tryLock();
                assertNotNull(lock, "session.lock is still held after createBackup returned");
                lock.release();
            }
        }
    }

    // ---------------------------------------------------------------------
    // T6: unknown backupId carries the real snapshot ids
    // ---------------------------------------------------------------------

    @Test
    void unknownBackupIdListsTheMostRecentRealSnapshotIds() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            makeWorld(fx, "Inst", "MyWorld", "data");
            // Fabricate six snapshot folders directly (distinct second-granularity timestamps
            // without sleeping): listBackups() recognizes them purely by folder-name pattern.
            Path backupRoot = fx.repository().getRunDirectory("Inst")
                    .resolve(WorldBackupManager.BACKUP_DIR_NAME).resolve("MyWorld");
            for (int day = 1; day <= 6; day++) {
                Files.createDirectories(backupRoot.resolve("2026010" + day + "-120000"));
            }

            IOException notFound = assertThrows(IOException.class,
                    () -> WorldBackupManager.restore("Inst", "MyWorld", "19990101-000000", 0));

            String message = notFound.getMessage();
            assertTrue(ToolFailures.isWellFormedEnvelope(message), "not a well-formed envelope: " + message);
            assertTrue(message.contains("Retryable: yes"), "unexpected classification: " + message);
            for (int day = 2; day <= 6; day++) {
                assertTrue(message.contains("2026010" + day + "-120000"),
                        "missing recent snapshot id in: " + message);
            }
            assertFalse(message.contains("20260101-120000"),
                    "only the 5 most recent ids should be listed: " + message);
            assertTrue(message.contains("worlds_backup_list"), "should point at the listing action: " + message);
        }
    }

    @Test
    void unknownBackupIdWithNoSnapshotsAtAllIsTerminal() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            makeWorld(fx, "Inst", "MyWorld", "data");

            IOException notFound = assertThrows(IOException.class,
                    () -> WorldBackupManager.restore("Inst", "MyWorld", "19990101-000000", 0));

            String message = notFound.getMessage();
            assertTrue(ToolFailures.isWellFormedEnvelope(message), "not a well-formed envelope: " + message);
            assertTrue(message.contains("Retryable: no"), "unexpected classification: " + message);
            assertTrue(message.contains("worlds_backup_create"),
                    "the non-retry way out must be offered: " + message);
        }
    }

    // ---------------------------------------------------------------------
    // G5: mid-swap marker + startup scan
    // ---------------------------------------------------------------------

    @Test
    void successfulRestoreLeavesNoMarkerAndNoLeftovers() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            Path worldDir = makeWorld(fx, "Inst", "MyWorld", "v1");
            String backupId = WorldBackupManager.createBackup("Inst", "MyWorld", 0).id();
            Files.writeString(worldDir.resolve("level.dat"), "v2"); // diverge the live world

            WorldBackupManager.RestoreResult result = WorldBackupManager.restore("Inst", "MyWorld", backupId, 0);

            assertEquals("v1", Files.readString(worldDir.resolve("level.dat")),
                    "the snapshot content must be back in place");
            assertNotNull(result.safetyBackupId(), "the pre-restore world must have been snapshotted");
            assertTrue(WorldBackupManager.scanInterruptedRestores("Inst").isEmpty(),
                    "a completed restore must leave no marker or set-aside directories");
        }
    }

    @Test
    void crashBetweenTheTwoMovesLeavesMarkerAndScanFindsEverything() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            Path worldDir = makeWorld(fx, "Inst", "MyWorld", "v1");
            String backupId = WorldBackupManager.createBackup("Inst", "MyWorld", 0).id();

            WorldBackupManager.crashBetweenMovesForTesting = () -> {
                throw new IllegalStateException("simulated hard crash mid-swap");
            };
            assertThrows(IllegalStateException.class,
                    () -> WorldBackupManager.restore("Inst", "MyWorld", backupId, 0));

            Path savesDir = worldDir.getParent();
            assertFalse(Files.exists(worldDir), "mid-swap, saves/<world> is absent — that IS the hazard");
            assertTrue(Files.isDirectory(savesDir.resolve(".MyWorld.replaced")),
                    "the pre-restore world must be preserved in the set-aside directory");
            assertEquals("v1", Files.readString(savesDir.resolve(".MyWorld.replaced").resolve("level.dat")));
            Path marker = savesDir.resolve(".MyWorld" + WorldBackupManager.RESTORE_IN_PROGRESS_SUFFIX);
            assertTrue(Files.isRegularFile(marker), "the mid-swap marker must survive the crash");
            assertTrue(Files.readString(marker).contains(backupId), "the marker must record the backupId");

            List<WorldBackupManager.InterruptedRestoreLeftover> leftovers =
                    WorldBackupManager.scanInterruptedRestores("Inst");
            Set<String> kinds = leftovers.stream()
                    .map(WorldBackupManager.InterruptedRestoreLeftover::kind)
                    .collect(Collectors.toSet());
            assertEquals(Set.of("replaced", "restoring", "restore-in-progress"), kinds,
                    "the scan must find all three leftover kinds: " + leftovers);
            assertTrue(leftovers.stream()
                            .allMatch(leftover -> leftover.world().equals("MyWorld")),
                    "every leftover must be attributed to the right world: " + leftovers);
        }
    }

    @Test
    void scanOnACleanInstanceFindsNothing() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            makeWorld(fx, "Inst", "MyWorld", "data");

            assertTrue(WorldBackupManager.scanInterruptedRestores("Inst").isEmpty());
        }
    }

    // ---------------------------------------------------------------------
    // Candidate enumeration: createBackup's missing-world and resolveRunDirectory's
    // missing-instance failures now carry the real names, matching the rest of the tool suite.
    // ---------------------------------------------------------------------

    @Test
    void createBackupMissingWorldFailsWithCandidateEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            makeWorld(fx, "Inst", "RealWorldA", "data");
            makeWorld(fx, "Inst", "RealWorldB", "data");

            IOException notFound = assertThrows(IOException.class,
                    () -> WorldBackupManager.createBackup("Inst", "NoSuchWorld", 0));

            String message = notFound.getMessage();
            assertTrue(ToolFailures.isWellFormedEnvelope(message), "not a well-formed envelope: " + message);
            assertTrue(message.contains("was not found"), message);
            assertTrue(message.contains("RealWorldA") && message.contains("RealWorldB"),
                    "must list the real world names: " + message);
        }
    }

    @Test
    void missingInstanceFailsWithCandidateEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");

            IOException notFound = assertThrows(IOException.class,
                    () -> WorldBackupManager.createBackup("DoesNotExist", "MyWorld", 0));

            String message = notFound.getMessage();
            assertTrue(ToolFailures.isWellFormedEnvelope(message), "not a well-formed envelope: " + message);
            assertTrue(message.contains("does not exist"), message);
            assertTrue(message.contains("Existing"), "must list the real instance names: " + message);
        }
    }
}
