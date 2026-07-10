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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pure-JVM coverage for [GameResourceGuard]: session.lock detection and whole-run lock holding
/// are exercised against a JUnit temp directory with a real {@link FileChannel#tryLock()} holder
/// simulating a running game (a same-JVM holder trips the {@link OverlappingFileLockException}
/// branch, which the guard treats as locked — same as the native `World#isLocked`); the
/// instance-process probe is swapped via the test hook so no game process is needed. All
/// rejection texts are locked to the unified ToolFailures envelope format
/// (`"Retryable:"` + `"Next:"`).
public final class GameResourceGuardTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void restoreProbe() {
        GameResourceGuard.setInstanceRunningProbeForTesting(null);
    }

    private Path newWorldDir(String name) throws IOException {
        Path worldDir = tempDir.resolve(name);
        Files.createDirectories(worldDir);
        return worldDir;
    }

    // ---------------------------------------------------------------------
    // checkInstanceNotRunning
    // ---------------------------------------------------------------------

    @Test
    void runningInstanceIsRejectedWithEnvelope() {
        GameResourceGuard.setInstanceRunningProbeForTesting("MyPack"::equals);

        String rejection = GameResourceGuard.checkInstanceNotRunning("MyPack");

        assertNotNull(rejection);
        assertTrue(ToolFailures.isWellFormedEnvelope(rejection), "not a well-formed envelope: " + rejection);
        assertTrue(rejection.contains("Retryable: later"), "unexpected classification: " + rejection);
        assertTrue(rejection.contains("Next:"), "missing Next segment: " + rejection);
        assertTrue(rejection.contains("MyPack"), "should name the instance: " + rejection);
        assertTrue(rejection.contains("game(action=\"stop\", instance=\"MyPack\")"),
                "should point at the stop action: " + rejection);
    }

    @Test
    void idleInstancePassesCheck() {
        GameResourceGuard.setInstanceRunningProbeForTesting("MyPack"::equals);

        assertNull(GameResourceGuard.checkInstanceNotRunning("OtherPack"));
    }

    // ---------------------------------------------------------------------
    // checkWorldNotLocked
    // ---------------------------------------------------------------------

    @Test
    void worldWithoutSessionLockFilePassesCheck() throws Exception {
        Path worldDir = newWorldDir("FreshWorld");

        assertNull(GameResourceGuard.checkWorldNotLocked(worldDir, "FreshWorld"));
    }

    @Test
    void worldWithUnheldSessionLockFilePassesCheck() throws Exception {
        Path worldDir = newWorldDir("IdleWorld");
        Files.writeString(worldDir.resolve("session.lock"), "☃");

        assertNull(GameResourceGuard.checkWorldNotLocked(worldDir, "IdleWorld"));
    }

    @Test
    void worldWithHeldSessionLockIsRejectedWithEnvelope() throws Exception {
        Path worldDir = newWorldDir("BusyWorld");
        Path lockFile = worldDir.resolve("session.lock");
        try (FileChannel holder = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held = holder.tryLock();
            assertNotNull(held, "test setup: could not take the session lock");

            String rejection = GameResourceGuard.checkWorldNotLocked(worldDir, "BusyWorld");

            assertNotNull(rejection);
            assertTrue(ToolFailures.isWellFormedEnvelope(rejection), "not a well-formed envelope: " + rejection);
            assertTrue(rejection.contains("Retryable: later"), "unexpected classification: " + rejection);
            assertTrue(rejection.contains("BusyWorld"), "should name the world: " + rejection);
            assertTrue(rejection.contains("session.lock"), "should explain the mechanism: " + rejection);
        }
    }

    // ---------------------------------------------------------------------
    // withWorldLock
    // ---------------------------------------------------------------------

    @Test
    void withWorldLockRunsActionWhileHoldingTheLock() throws Exception {
        Path worldDir = newWorldDir("EditedWorld");
        Path lockFile = worldDir.resolve("session.lock");

        String result = GameResourceGuard.withWorldLock(worldDir, "EditedWorld", () -> {
            // While the action runs, the guard must HOLD the lock: a same-JVM attempt to
            // re-acquire it must fail with OverlappingFileLockException.
            try (FileChannel probe = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
                assertThrows(OverlappingFileLockException.class, probe::tryLock,
                        "session.lock is not held during the action");
            }
            return "done";
        });

        assertEquals("done", result);
        assertLockReleasable(lockFile); // and released once the action returns
    }

    @Test
    void withWorldLockOnBusyWorldThrowsWorldBusyWithEnvelope() throws Exception {
        Path worldDir = newWorldDir("PlayedWorld");
        Path lockFile = worldDir.resolve("session.lock");
        try (FileChannel holder = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held = holder.tryLock();
            assertNotNull(held, "test setup: could not take the session lock");
            AtomicBoolean actionRan = new AtomicBoolean(false);

            GameResourceGuard.WorldBusyException busy = assertThrows(
                    GameResourceGuard.WorldBusyException.class,
                    () -> GameResourceGuard.withWorldLock(worldDir, "PlayedWorld", () -> {
                        actionRan.set(true);
                        return null;
                    }));

            assertFalse(actionRan.get(), "action must not run when the world is busy");
            assertTrue(ToolFailures.isWellFormedEnvelope(busy.getMessage()),
                    "not a well-formed envelope: " + busy.getMessage());
            assertTrue(busy.getMessage().contains("PlayedWorld"), "should name the world: " + busy.getMessage());
            assertTrue(busy.getMessage().contains("Retryable: later"),
                    "unexpected classification: " + busy.getMessage());
        }
    }

    @Test
    void withWorldLockReleasesLockWhenActionThrows() throws Exception {
        Path worldDir = newWorldDir("ThrowingWorld");
        Path lockFile = worldDir.resolve("session.lock");

        IOException thrown = assertThrows(IOException.class,
                () -> GameResourceGuard.withWorldLock(worldDir, "ThrowingWorld", () -> {
                    throw new IOException("boom");
                }));

        assertEquals("boom", thrown.getMessage()); // the action's own failure propagates untouched
        assertLockReleasable(lockFile);
    }

    @Test
    void withWorldLockOnMissingWorldDirReportsNotFoundInsteadOfBusy() {
        Path missing = tempDir.resolve("DoesNotExist");

        assertThrows(NoSuchFileException.class,
                () -> GameResourceGuard.withWorldLock(missing, "DoesNotExist", () -> null));
    }

    // ---------------------------------------------------------------------
    // rejectionText
    // ---------------------------------------------------------------------

    @Test
    void rejectionTextsForBothKindsAreWellFormedEnvelopes() {
        for (GameResourceGuard.Kind kind : GameResourceGuard.Kind.values()) {
            String text = GameResourceGuard.rejectionText(kind, "Target", null);
            assertTrue(ToolFailures.isWellFormedEnvelope(text), kind + " not a well-formed envelope: " + text);
            assertTrue(text.contains("Retryable: later"), kind + " unexpected classification: " + text);
            assertTrue(text.contains("Next:"), kind + " missing Next segment: " + text);
            assertTrue(text.contains("Target"), kind + " should name the resource: " + text);
            assertTrue(text.contains("nothing was changed"), kind + " should state no side effects: " + text);
        }
    }

    @Test
    void rejectionTextAppendsContextIntoWhatSegment() {
        String text = GameResourceGuard.rejectionText(GameResourceGuard.Kind.INSTANCE_RUNNING,
                "MyPack", "the delete would have removed 42 files");

        assertTrue(text.contains("; the delete would have removed 42 files"),
                "context not appended: " + text);
        assertTrue(text.indexOf("42 files") < text.indexOf("Retryable:"),
                "context must live in the what-segment: " + text);
        assertTrue(ToolFailures.isWellFormedEnvelope(text), "not a well-formed envelope: " + text);
    }

    @Test
    void rejectionTextIgnoresBlankContext() {
        String withNull = GameResourceGuard.rejectionText(GameResourceGuard.Kind.WORLD_LOCKED, "W", null);
        String withBlank = GameResourceGuard.rejectionText(GameResourceGuard.Kind.WORLD_LOCKED, "W", "   ");

        assertEquals(withNull, withBlank);
    }

    /// Asserts the guard is no longer holding `session.lock`: taking (and releasing) the lock
    /// from a fresh channel must succeed.
    private static void assertLockReleasable(Path lockFile) throws IOException {
        try (FileChannel after = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
            FileLock lock = after.tryLock();
            assertNotNull(lock, "session.lock is still held after withWorldLock returned");
            lock.release();
        }
    }
}
