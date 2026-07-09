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

import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// Versioned world-backup engine for the AI agent.
///
/// ## What this IS (current scope)
/// A *versioned, timestamped, FULL-COPY* backup engine with retention pruning:
/// every backup recursively copies the entire `saves/<world>` directory into
/// `<runDir>/ai-world-backups/<world>/<yyyyMMdd-HHmmss>/`, then deletes the
/// oldest snapshots beyond the configured retention count.
///
/// ## What this is NOT (honest disclaimer)
/// This is **NOT** an incremental / deduplicating / git-style engine. Each
/// snapshot is an independent full copy, so disk usage grows linearly with the
/// number of retained snapshots and snapshots share no storage. A future
/// iteration could switch to a content-addressed / incremental
/// store (chunk dedup, hard-linked unchanged files, or an embedded git repo)
/// without changing the tool surface.
///
/// All paths are confined to the instance run directory (normalize + startsWith
/// validation), mirroring the existing world tools.
@NotNullByDefault
public final class WorldBackupManager {

    /// The per-instance directory under which all AI world backups live.
    public static final String BACKUP_DIR_NAME = "ai-world-backups";

    /// Directory (under {@link #BACKUP_DIR_NAME}) that stores pending first-launch
    /// safety-backup markers for freshly-imported worlds — see
    /// {@link #markPendingFirstLaunchBackup} / {@link #consumePendingFirstLaunchBackups}.
    private static final String PENDING_FIRST_LAUNCH_DIR_NAME = "pending-first-launch";

    /// Timestamp folder format: `yyyyMMdd-HHmmss`.
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /// Recognizes a backup snapshot folder name (a timestamp, optionally with a
    /// `-N` uniqueness suffix when two backups land in the same second).
    private static final Pattern SNAPSHOT_ID = Pattern.compile("\\d{8}-\\d{6}(-\\d+)?");

    private WorldBackupManager() {
    }

    /// Metadata about one stored snapshot.
    public record BackupInfo(String id, int fileCount, long sizeBytes) {
    }

    /// Outcome of creating a snapshot.
    public record BackupResult(Path backupPath, String id, int fileCount, long sizeBytes, int prunedCount) {
    }

    /// Outcome of a restore. {@code safetyBackupId} is the snapshot taken of the
    /// *current* world right before it was overwritten (or {@code null} if the
    /// world did not exist yet, so there was nothing to protect).
    public record RestoreResult(Path restoredWorldPath, @Nullable String safetyBackupId, int fileCount) {
    }

    /// Outcome of {@link #consumePendingFirstLaunchBackups}: which pending worlds were
    /// successfully snapshotted, and which still have a marker because their backup failed
    /// (and will be retried on the next launch attempt).
    public record PendingBackupResult(List<String> backedUpWorlds, List<String> failedWorlds) {
        public boolean isEmpty() {
            return backedUpWorlds.isEmpty() && failedWorlds.isEmpty();
        }
    }

    // ---- Instance / path resolution (path-confined) --------------------------------

    /// Resolves and validates the run directory for an instance.
    ///
    /// @param instance the instance id, or {@code null} to use the currently
    ///                 selected instance
    /// @throws IOException if no profile/instance is available or the instance
    ///                    does not exist
    public static Path resolveRunDirectory(@Nullable String instance) throws IOException {
        Profile profile;
        try {
            profile = Profiles.getSelectedProfile();
        } catch (Throwable e) {
            throw new IOException("No profile is currently selected: " + e.getMessage());
        }
        HMCLGameRepository repository = profile.getRepository();

        String resolved;
        if (instance != null && !instance.trim().isEmpty()) {
            resolved = instance.trim();
        } else {
            @Nullable String selected = Profiles.getSelectedInstance();
            if (selected == null) {
                throw new IOException("No instance is selected and no 'instance' parameter was given.");
            }
            resolved = selected;
        }

        try {
            if (!repository.hasVersion(resolved)) {
                throw new IOException("Instance '" + resolved + "' does not exist in the selected profile.");
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("Failed to verify instance '" + resolved + "': " + e.getMessage());
        }

        try {
            return repository.getRunDirectory(resolved).toAbsolutePath().normalize();
        } catch (Throwable e) {
            throw new IOException("Failed to resolve the run directory of '" + resolved + "': " + e.getMessage());
        }
    }

    /// Resolves `saves/<world>` under the run directory, confined to `saves/`.
    private static Path resolveWorldDir(Path runDir, String world) throws IOException {
        Path savesDir = runDir.resolve("saves").toAbsolutePath().normalize();
        Path worldDir = savesDir.resolve(world).toAbsolutePath().normalize();
        if (!worldDir.startsWith(savesDir) || worldDir.equals(savesDir)) {
            throw new IOException("Illegal world name '" + world + "' (path escapes the saves directory).");
        }
        return worldDir;
    }

    /// Resolves the per-world backup root, confined to the backup directory.
    private static Path resolveBackupRoot(Path runDir, String world) throws IOException {
        Path backupsBase = runDir.resolve(BACKUP_DIR_NAME).toAbsolutePath().normalize();
        Path worldBackupRoot = backupsBase.resolve(world).toAbsolutePath().normalize();
        if (!worldBackupRoot.startsWith(backupsBase) || worldBackupRoot.equals(backupsBase)) {
            throw new IOException("Illegal world name '" + world + "' (path escapes the backup directory).");
        }
        return worldBackupRoot;
    }

    /// Resolves the directory that stores pending first-launch markers.
    private static Path pendingMarkerDir(Path runDir) {
        return runDir.resolve(BACKUP_DIR_NAME).resolve(PENDING_FIRST_LAUNCH_DIR_NAME).toAbsolutePath().normalize();
    }

    /// Resolves one world's pending-marker file, confined to the marker directory.
    private static Path resolvePendingMarker(Path markerDir, String world) throws IOException {
        Path marker = markerDir.resolve(world).toAbsolutePath().normalize();
        if (!marker.startsWith(markerDir) || marker.equals(markerDir)) {
            throw new IOException("Illegal world name '" + world + "' (path escapes the marker directory).");
        }
        return marker;
    }

    // ---- Public engine API ---------------------------------------------------------

    /// Creates a full-copy, timestamped snapshot of `saves/<world>` and prunes
    /// the oldest snapshots beyond {@code retentionCount}.
    ///
    /// @param instance       instance id (nullable → selected instance)
    /// @param world          the save folder name under `saves/`
    /// @param retentionCount keep at most this many newest snapshots
    ///                       ({@code <= 0} disables pruning)
    public static BackupResult createBackup(@Nullable String instance, String world, int retentionCount)
            throws IOException {
        Path runDir = resolveRunDirectory(instance);
        Path worldDir = resolveWorldDir(runDir, world);
        if (!Files.isDirectory(worldDir)) {
            throw new IOException("World '" + world + "' was not found at: " + worldDir);
        }
        Path backupRoot = resolveBackupRoot(runDir, world);
        Files.createDirectories(backupRoot);

        String id = uniqueSnapshotId(backupRoot);
        Path target = backupRoot.resolve(id);

        long[] counters; // [0] files, [1] bytes
        try {
            counters = copyTree(worldDir, target);
        } catch (IOException e) {
            // A failed/partial copy (e.g. disk full) must not leave a truncated snapshot folder
            // behind — listBackups()/prune() would otherwise treat it as a complete, restorable
            // backup. Best-effort cleanup; if even that fails, surface both.
            try {
                if (Files.exists(target)) {
                    deleteTree(target);
                }
            } catch (IOException cleanupFailed) {
                e.addSuppressed(cleanupFailed);
            }
            throw e;
        }

        int pruned = prune(backupRoot, retentionCount);
        return new BackupResult(target, id, (int) counters[0], counters[1], pruned);
    }

    /// Lists all snapshots of a world, newest first.
    public static List<BackupInfo> listBackups(@Nullable String instance, String world) throws IOException {
        Path runDir = resolveRunDirectory(instance);
        Path backupRoot = resolveBackupRoot(runDir, world);
        List<BackupInfo> result = new ArrayList<>();
        if (!Files.isDirectory(backupRoot)) {
            return result;
        }
        try (Stream<Path> children = Files.list(backupRoot)) {
            for (Path snapshot : (Iterable<Path>) children::iterator) {
                if (!Files.isDirectory(snapshot)) {
                    continue;
                }
                String id = snapshot.getFileName().toString();
                if (!SNAPSHOT_ID.matcher(id).matches()) {
                    continue;
                }
                long[] stats = tallyTree(snapshot);
                result.add(new BackupInfo(id, (int) stats[0], stats[1]));
            }
        }
        result.sort(Comparator.comparing(BackupInfo::id, WorldBackupManager::compareSnapshotIds).reversed());
        return result;
    }

    /// Restores a snapshot over the live world. To guard against restoring the
    /// wrong snapshot, the CURRENT world is first snapshotted (if it exists),
    /// then replaced with the chosen backup.
    ///
    /// HIGH RISK: this overwrites the existing `saves/<world>`.
    ///
    /// @param backupId the snapshot folder name (timestamp id) to restore
    public static RestoreResult restore(@Nullable String instance, String world, String backupId, int retentionCount)
            throws IOException {
        Path runDir = resolveRunDirectory(instance);
        Path worldDir = resolveWorldDir(runDir, world);
        Path backupRoot = resolveBackupRoot(runDir, world);

        // Validate the requested snapshot id and confine its path.
        if (!SNAPSHOT_ID.matcher(backupId).matches()) {
            throw new IOException("Invalid backupId '" + backupId + "' (expected a snapshot timestamp like 20260629-153000).");
        }
        Path snapshot = backupRoot.resolve(backupId).toAbsolutePath().normalize();
        if (!snapshot.startsWith(backupRoot) || !Files.isDirectory(snapshot)) {
            throw new IOException("Backup '" + backupId + "' of world '" + world + "' was not found at: " + snapshot);
        }

        // 1) Stage the chosen snapshot into a temp dir FIRST, so a copy failure can never destroy the
        //    live world (we only delete the world once the new copy is fully staged).
        Path staging = worldDir.resolveSibling("." + world + ".restoring");
        if (Files.exists(staging)) {
            deleteTree(staging);
        }
        long[] counters = copyTree(snapshot, staging);

        // 2) Safety: snapshot the current world before overwriting it. Pruning is DISABLED here
        //    (retentionCount 0) so this safety backup can never evict the snapshot being restored.
        @Nullable String safetyId = null;
        @Nullable Path replaced = null;
        if (Files.isDirectory(worldDir)) {
            BackupResult safety = createBackup(instance, world, 0);
            safetyId = safety.id();
            // Set the live world ASIDE with a single rename instead of deleting it: a rename has
            // no half-deleted state, and if anything below fails the original world is renamed
            // straight back. The old delete-then-move flow could leave saves/ without the world
            // (or with half of it) after a crash or a locked file, with no automatic recovery.
            replaced = worldDir.resolveSibling("." + world + ".replaced");
            if (Files.exists(replaced)) {
                deleteTree(replaced);
            }
            try {
                Files.move(worldDir, replaced);
            } catch (IOException moveFailed) {
                deleteTree(staging); // clean up; the live world is untouched
                throw new IOException("World '" + world + "' is in use (is the game running?) — restore aborted; "
                        + "the current world was NOT touched. Close the world/game and retry.", moveFailed);
            }
        }

        // 3) Swap the staged copy into place (a rename within saves/ is atomic on the same filesystem;
        //    fall back to copy+delete if the platform refuses the move). On failure, roll the
        //    original world back into place before propagating.
        try {
            try {
                Files.move(staging, worldDir);
            } catch (IOException moveFailed) {
                copyTree(staging, worldDir);
                deleteTree(staging);
            }
        } catch (IOException swapFailed) {
            if (replaced != null) {
                try {
                    if (Files.exists(worldDir)) {
                        deleteTree(worldDir); // partial copy from the failed fallback
                    }
                    Files.move(replaced, worldDir); // roll the original back
                } catch (IOException rollbackFailed) {
                    throw new IOException("Restore failed AND rollback failed — the original world is preserved at "
                            + replaced + " (rename it back to '" + world + "' to recover).", swapFailed);
                }
            }
            throw swapFailed;
        }

        // 4) The staged copy is fully in place — the restore itself has ALREADY fully succeeded at
        //    this point (saves/<world> now IS the snapshot). Dropping the set-aside original and
        //    pruning old snapshots are both best-effort cleanup from here on: e.g. if the game
        //    process still holds an open handle into `replaced`, deleteTree() can throw even
        //    though the restore worked — that must not turn an already-completed restore into a
        //    reported failure (with the trailing prune() silently skipped as a side effect of the
        //    exception propagating out early).
        if (replaced != null && Files.exists(replaced)) {
            try {
                deleteTree(replaced);
            } catch (IOException cleanupFailed) {
                // best-effort: leave the stale copy at `replaced` rather than failing a completed restore
            }
        }
        try {
            prune(backupRoot, retentionCount);
        } catch (IOException pruneFailed) {
            // best-effort: a pruning failure must not mask an already-successful restore
        }
        return new RestoreResult(worldDir, safetyId, (int) counters[0]);
    }

    /// Marks {@code world} as needing an automatic safety backup before its NEXT launch.
    ///
    /// Called by `import_world` right after a fresh save is successfully extracted: an
    /// old/incompatible world can be silently truncated or corrupted the very first time
    /// Minecraft opens it, and until now there was no backup to fall back to in that case.
    /// The marker is a plain empty file under `<runDir>/ai-world-backups/pending-first-launch/`
    /// so it survives an app restart between the import and the eventual launch.
    ///
    /// @see #consumePendingFirstLaunchBackups(String, int)
    public static void markPendingFirstLaunchBackup(@Nullable String instance, String world) throws IOException {
        Path runDir = resolveRunDirectory(instance);
        Path markerDir = pendingMarkerDir(runDir);
        Files.createDirectories(markerDir);
        Path marker = resolvePendingMarker(markerDir, world);
        Files.writeString(marker, "");
    }

    /// Consumes (backs up and clears) every pending first-launch marker for {@code instance},
    /// taking a normal versioned snapshot of each still-existing world via {@link #createBackup}.
    ///
    /// Called by `launch_instance` right before the actual game launch is dispatched — this is
    /// what turns {@link #markPendingFirstLaunchBackup} into an actual safety net.
    ///
    /// Best-effort per world: a world whose backup fails KEEPS its marker (so it is retried on
    /// the next launch attempt) and is reported in {@link PendingBackupResult#failedWorlds()}
    /// rather than throwing — a failed safety net must never block the user from playing.
    /// A world that no longer exists under `saves/` (deleted/renamed since import) has nothing
    /// left to protect, so its marker is silently dropped.
    ///
    /// Never throws: any failure resolving the instance itself (no profile/instance selected,
    /// etc.) yields an empty result rather than propagating, since the caller has already
    /// validated the instance before launching.
    public static PendingBackupResult consumePendingFirstLaunchBackups(@Nullable String instance, int retentionCount) {
        List<String> backedUp = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try {
            Path runDir = resolveRunDirectory(instance);
            Path markerDir = pendingMarkerDir(runDir);
            if (!Files.isDirectory(markerDir)) {
                return new PendingBackupResult(backedUp, failed);
            }

            List<String> worlds = new ArrayList<>();
            try (Stream<Path> children = Files.list(markerDir)) {
                for (Path marker : (Iterable<Path>) children::iterator) {
                    if (Files.isRegularFile(marker)) {
                        worlds.add(marker.getFileName().toString());
                    }
                }
            }

            for (String world : worlds) {
                Path marker;
                Path worldDir;
                try {
                    marker = resolvePendingMarker(markerDir, world);
                    worldDir = resolveWorldDir(runDir, world);
                } catch (IOException illegalName) {
                    failed.add(world);
                    continue;
                }
                if (!Files.isDirectory(worldDir)) {
                    // The world was deleted/renamed since import: nothing left to protect.
                    try {
                        Files.deleteIfExists(marker);
                    } catch (IOException ignored) {
                        // best-effort cleanup of a now-meaningless marker
                    }
                    continue;
                }
                try {
                    createBackup(instance, world, retentionCount);
                    Files.deleteIfExists(marker);
                    backedUp.add(world);
                } catch (IOException backupFailed) {
                    failed.add(world);
                }
            }
        } catch (IOException resolveFailed) {
            // No profile/instance available for the given id — nothing to protect.
        }
        return new PendingBackupResult(backedUp, failed);
    }

    // ---- Internals -----------------------------------------------------------------

    /// Compares two snapshot ids chronologically. The fixed-width `yyyyMMdd-HHmmss` prefix (15
    /// chars) sorts correctly as a plain string (same width, lexicographic order == chronological
    /// order), but a same-second collision's `-N` uniqueness suffix does NOT — plain string
    /// comparison puts `"...-10"` before `"...-2"`. Parses that numeric suffix (0 when absent) and
    /// compares it as an integer instead, so ordering stays correct once 10+ snapshots land within
    /// the same second.
    private static int compareSnapshotIds(String a, String b) {
        String prefixA = a.length() >= 15 ? a.substring(0, 15) : a;
        String prefixB = b.length() >= 15 ? b.substring(0, 15) : b;
        int cmp = prefixA.compareTo(prefixB);
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(snapshotSuffix(a), snapshotSuffix(b));
    }

    /// The numeric `-N` uniqueness suffix of a snapshot id (0 when absent or unparseable).
    private static int snapshotSuffix(String id) {
        int dash = id.indexOf('-', 15);
        if (dash < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(id.substring(dash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /// Produces a snapshot id for the current instant, appending `-N` if a folder
    /// with that timestamp already exists (two backups within the same second).
    private static String uniqueSnapshotId(Path backupRoot) {
        String base = LocalDateTime.now().format(TIMESTAMP);
        if (!Files.exists(backupRoot.resolve(base))) {
            return base;
        }
        for (int i = 1; i < 1000; i++) {
            String candidate = base + "-" + i;
            if (!Files.exists(backupRoot.resolve(candidate))) {
                return candidate;
            }
        }
        return base + "-" + System.nanoTime();
    }

    /// Recursively copies {@code source} into {@code target}.
    ///
    /// @return a 2-element array: [0] = files copied, [1] = total bytes copied
    private static long[] copyTree(Path source, Path target) throws IOException {
        long[] counters = new long[2];
        Files.createDirectories(target);
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Path parent = destination.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(path, destination);
                    counters[0]++;
                    try {
                        counters[1] += Files.size(destination);
                    } catch (Throwable ignored) {
                        // size is best-effort
                    }
                }
            }
        }
        return counters;
    }

    /// Tallies a tree: [0] = file count, [1] = total bytes.
    private static long[] tallyTree(Path root) {
        long[] stats = new long[2];
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(path)) {
                    stats[0]++;
                    try {
                        stats[1] += Files.size(path);
                    } catch (Throwable ignored) {
                        // best-effort
                    }
                }
            }
        } catch (Throwable ignored) {
            // best-effort tally
        }
        return stats;
    }

    /// Deletes snapshots beyond the newest {@code retentionCount}.
    ///
    /// @return the number of snapshots deleted
    private static int prune(Path backupRoot, int retentionCount) throws IOException {
        if (retentionCount <= 0) {
            return 0;
        }
        List<Path> snapshots = new ArrayList<>();
        try (Stream<Path> children = Files.list(backupRoot)) {
            for (Path snapshot : (Iterable<Path>) children::iterator) {
                if (Files.isDirectory(snapshot)
                        && SNAPSHOT_ID.matcher(snapshot.getFileName().toString()).matches()) {
                    snapshots.add(snapshot);
                }
            }
        }
        if (snapshots.size() <= retentionCount) {
            return 0;
        }
        // Newest first; delete everything past the retention window.
        snapshots.sort(Comparator.comparing((Path p) -> p.getFileName().toString(),
                WorldBackupManager::compareSnapshotIds).reversed());
        int pruned = 0;
        for (int i = retentionCount; i < snapshots.size(); i++) {
            deleteTree(snapshots.get(i));
            pruned++;
        }
        return pruned;
    }

    /// Recursively deletes a directory tree.
    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /// Formats a byte count as a short human-readable string (B/KB/MB/GB).
    public static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format("%.1f %s", value, units[unit]);
    }
}
