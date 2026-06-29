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
/// iteration could switch to a fastback-style content-addressed / incremental
/// store (chunk dedup, hard-linked unchanged files, or an embedded git repo)
/// without changing the tool surface.
///
/// All paths are confined to the instance run directory (normalize + startsWith
/// validation), mirroring the existing world tools.
@NotNullByDefault
public final class WorldBackupManager {

    /// The per-instance directory under which all AI world backups live.
    public static final String BACKUP_DIR_NAME = "ai-world-backups";

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

        long[] counters = copyTree(worldDir, target); // [0] files, [1] bytes

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
        result.sort(Comparator.comparing(BackupInfo::id).reversed());
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

        // 1) Safety: snapshot the current world before overwriting it.
        @Nullable String safetyId = null;
        if (Files.isDirectory(worldDir)) {
            BackupResult safety = createBackup(instance, world, retentionCount);
            safetyId = safety.id();
            deleteTree(worldDir);
        }

        // 2) Copy the chosen snapshot into place.
        long[] counters = copyTree(snapshot, worldDir);
        return new RestoreResult(worldDir, safetyId, (int) counters[0]);
    }

    // ---- Internals -----------------------------------------------------------------

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
        snapshots.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
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
