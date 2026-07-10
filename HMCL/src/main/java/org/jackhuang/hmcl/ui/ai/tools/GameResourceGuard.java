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
import org.jackhuang.hmcl.game.LauncherHelper;
import org.jackhuang.hmcl.util.io.IOUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Predicate;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Single source of truth for "is this game resource currently occupied?" checks across the AI
/// tool corpus (style-wise the sibling of {@link org.jackhuang.hmcl.ai.tools.CriticalOperations},
/// which is the single source of truth for "is this operation catastrophic?").
///
/// Before this class existed the repository had four occupancy mechanisms of wildly different
/// strength, and each tool picked one (or none) at random:
///   1. HMCL's own per-instance process table — {@link LauncherHelper#isInstanceRunning(String)}
///      (precise, but only for games HMCL itself launched);
///   2. an explicit `session.lock` {@code tryLock} probe — {@code World#isLocked()}, the same
///      mechanism the vanilla Minecraft client uses (strong, but "check-then-release" is a
///      TOCTOU window when the subsequent write does not keep holding the lock);
///   3. treating a failed {@code Files.move} as "in use" (unreliable — on Windows/NTFS a
///      directory rename usually succeeds even while child files are open);
///   4. no check at all.
///
/// This class funnels all of them into three canonical entry points:
///   - [#checkInstanceNotRunning(String)] — mechanism 1, for whole-instance operations
///     (delete/rename instance, mod file surgery, ...);
///   - [#checkWorldNotLocked(Path, String)] — mechanism 2 as a *pre-check* for operations that
///     cannot hold the lock across their whole run (e.g. restore that must rename the world
///     directory itself);
///   - [#withWorldLock(Path, String, LockedWorldAction)] — mechanism 2 done properly: acquires
///     the world's `session.lock` {@link FileChannel} and HOLDS it for the entire action,
///     mirroring the native `WorldManageUIUtils.getSessionLockChannel()` editing-session
///     discipline (and `World#lock()`'s acquisition semantics), which closes the TOCTOU window
///     of the check-then-write pattern.
///
/// Rejection texts are produced by [#rejectionText(Kind, String, String)] and follow the unified
/// tool-failure envelope from {@link ToolFailures} (model-visible, therefore English), always
/// classified `Retryable: later` with a concrete "stop the game, then retry" next step.
@NotNullByDefault
public final class GameResourceGuard {

    private GameResourceGuard() {
    }

    /// The kind of occupancy that caused a rejection — selects the envelope wording.
    public enum Kind {
        /// The instance has a live game process HMCL launched and still tracks.
        INSTANCE_RUNNING,
        /// The world directory's `session.lock` is held by a running game session.
        WORLD_LOCKED
    }

    /// The action run by [#withWorldLock(Path, String, LockedWorldAction)] while the world's
    /// `session.lock` channel is held. Return the value the caller needs (or `null`).
    @FunctionalInterface
    public interface LockedWorldAction<T> {
        T run() throws IOException;
    }

    /// Thrown by [#withWorldLock(Path, String, LockedWorldAction)] when the world's
    /// `session.lock` cannot be acquired (a game session holds it). [#getMessage()] is the
    /// ready-to-return rejection envelope — callers can surface it verbatim as the tool failure
    /// text.
    public static final class WorldBusyException extends IOException {
        WorldBusyException(String envelope) {
            super(envelope);
        }
    }

    /// What vanilla Minecraft (and `World#lock()`) stamps into `session.lock`: a snowman.
    private static final byte[] SESSION_LOCK_STAMP = "☃".getBytes(StandardCharsets.UTF_8);

    private static final String SESSION_LOCK_FILE = "session.lock";

    /// The live-process probe behind [#checkInstanceNotRunning(String)]. A lambda (not a method
    /// reference) so {@link LauncherHelper} is only class-loaded when a check actually runs;
    /// tests swap it via [#setInstanceRunningProbeForTesting(Predicate)].
    private static volatile Predicate<String> instanceRunningProbe =
            id -> LauncherHelper.isInstanceRunning(id);

    /// Test hook: replaces the live-process probe; `null` restores the default
    /// {@link LauncherHelper}-backed probe.
    static void setInstanceRunningProbeForTesting(@Nullable Predicate<String> probe) {
        instanceRunningProbe = probe != null ? probe : id -> LauncherHelper.isInstanceRunning(id);
    }

    /// Checks that instance {@code instanceId} has no live game process HMCL launched and still
    /// tracks (the same per-instance process table `game(action="list")`'s "(running)" marker and
    /// `game(action="stop")` use). Cannot see game processes started outside HMCL — callers whose
    /// operation touches `saves/` should additionally guard the world level via
    /// [#checkWorldNotLocked(Path, String)] / [#withWorldLock(Path, String, LockedWorldAction)].
    ///
    /// @param instanceId the instance/version id
    /// @return the ready-to-return rejection envelope when the instance is running, or `null`
    ///         when it is safe to proceed
    @Nullable
    public static String checkInstanceNotRunning(String instanceId) {
        return instanceRunningProbe.test(instanceId)
                ? rejectionText(Kind.INSTANCE_RUNNING, instanceId, null)
                : null;
    }

    /// Checks that the world at {@code worldDir} is not open in a running game, by probing its
    /// `session.lock` with the exact semantics of the native `World#isLocked()`: a held
    /// {@link FileChannel#tryLock()} (including a same-JVM holder, and an access-denied open)
    /// means locked; a missing `session.lock` means not locked; any other I/O error is logged
    /// and treated as not locked (this is an advisory pre-check — operations that can do so
    /// should hold the lock across their whole run via
    /// [#withWorldLock(Path, String, LockedWorldAction)] instead).
    ///
    /// @param worldDir the world directory (`saves/<world>`)
    /// @param label    the world name used in the rejection text
    /// @return the ready-to-return rejection envelope when the world is locked, or `null` when
    ///         it is safe to proceed
    @Nullable
    public static String checkWorldNotLocked(Path worldDir, String label) {
        return isSessionLockHeld(worldDir.resolve(SESSION_LOCK_FILE))
                ? rejectionText(Kind.WORLD_LOCKED, label, null)
                : null;
    }

    /// Runs {@code action} while HOLDING the world's `session.lock` {@link FileChannel} for the
    /// entire duration — the native `WorldManageUIUtils.getSessionLockChannel()` editing-session
    /// pattern — so a game launched mid-action cannot acquire the world and race the write.
    /// Acquisition mirrors `World#lock()`: the lock file is created/stamped and `tryLock`ed; any
    /// failure to do so (a held lock, a same-JVM holder, access denied, an I/O error while
    /// stamping) is treated as "world busy" and raised as [WorldBusyException], whose message is
    /// the ready-to-return rejection envelope. A missing world directory is NOT "busy" and
    /// surfaces as the original {@link NoSuchFileException} so callers report it truthfully.
    /// The channel (and with it the OS lock) is always released when the action returns or
    /// throws.
    ///
    /// @param worldDir the world directory (`saves/<world>`)
    /// @param label    the world name used in the rejection text
    /// @param action   the work to perform under the lock
    /// @return the action's result
    /// @throws WorldBusyException   when the world is occupied by a running game session
    /// @throws NoSuchFileException  when {@code worldDir} does not exist
    /// @throws IOException          whatever the action itself throws
    public static <T> T withWorldLock(Path worldDir, String label, LockedWorldAction<T> action) throws IOException {
        try (FileChannel channel = acquireSessionLock(worldDir, label)) {
            return action.run();
        }
    }

    /// Builds the unified rejection envelope for an occupancy denial. Model-visible, therefore
    /// English; always `Retryable: later` with a "stop the game / quit, then retry" next step.
    ///
    /// @param kind which occupancy mechanism rejected the operation
    /// @param name the instance id ([Kind#INSTANCE_RUNNING]) or world name ([Kind#WORLD_LOCKED])
    /// @param ctx  optional extra context appended to the "what happened" segment (e.g. what the
    ///             refused operation would have done); `null` or blank to omit
    /// @return the assembled envelope text
    public static String rejectionText(Kind kind, String name, @Nullable String ctx) {
        String suffix = ctx == null || ctx.isBlank() ? "" : "; " + ctx.trim();
        return switch (kind) {
            case INSTANCE_RUNNING -> ToolFailures.failureEnvelope(
                    "Instance '" + name + "' is currently running — HMCL is tracking a live game process for it. "
                            + "The operation was refused and nothing was changed" + suffix,
                    ToolFailures.Retryable.LATER,
                    "operating on a running instance would corrupt files the game is actively writing",
                    "Stop the game first — call game(action=\"stop\", instance=\"" + name
                            + "\") or ask the user to save and quit — then retry this call");
            case WORLD_LOCKED -> ToolFailures.failureEnvelope(
                    "World '" + name + "' is currently open in a running game (its session.lock is held). "
                            + "The operation was refused and nothing was changed" + suffix,
                    ToolFailures.Retryable.LATER,
                    "the world can be written safely once no game session holds its session.lock",
                    "Ask the user to quit to the title screen or close the game (game(action=\"stop\") "
                            + "force-stops an instance HMCL launched), then retry this call");
        };
    }

    // ---------------------------------------------------------------------
    // session.lock plumbing
    // ---------------------------------------------------------------------

    /// One-shot probe with `World#isLocked(Path)`'s exact semantics (see
    /// [#checkWorldNotLocked(Path, String)]).
    private static boolean isSessionLockHeld(Path sessionLockFile) {
        try (FileChannel channel = FileChannel.open(sessionLockFile, StandardOpenOption.WRITE)) {
            return channel.tryLock() == null;
        } catch (AccessDeniedException | OverlappingFileLockException e) {
            return true;
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            LOG.warning("Failed to probe session lock " + sessionLockFile, e);
            return false;
        }
    }

    /// Acquires the world's `session.lock` channel with `World#lock()`'s semantics (create,
    /// stamp the snowman, `tryLock`), mapping every "someone else holds it" signal — a null
    /// {@code tryLock} result, a same-JVM {@link OverlappingFileLockException}, an
    /// {@link AccessDeniedException} on open, or an I/O error while stamping (on Windows a
    /// foreign mandatory lock rejects the write itself) — to [WorldBusyException]. The returned
    /// channel must be closed to release the lock.
    private static FileChannel acquireSessionLock(Path worldDir, String label) throws IOException {
        Path lockFile = worldDir.resolve(SESSION_LOCK_FILE);
        FileChannel channel;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (AccessDeniedException e) {
            throw new WorldBusyException(rejectionText(Kind.WORLD_LOCKED, label, null));
        }
        boolean acquired = false;
        try {
            FileLock lock;
            try {
                channel.write(ByteBuffer.wrap(SESSION_LOCK_STAMP));
                channel.force(true);
                lock = channel.tryLock();
            } catch (OverlappingFileLockException | IOException e) {
                lock = null;
            }
            if (lock == null) {
                throw new WorldBusyException(rejectionText(Kind.WORLD_LOCKED, label, null));
            }
            acquired = true;
            return channel;
        } finally {
            if (!acquired) {
                IOUtils.closeQuietly(channel);
            }
        }
    }
}
