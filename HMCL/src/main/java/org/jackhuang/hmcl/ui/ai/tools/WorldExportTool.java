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

import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolParams;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.World;
import org.jackhuang.hmcl.game.WorldLockedException;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/// A tool that exports or duplicates a single-player world (a `saves/<world>` folder), dispatching
/// on the {@code action} parameter between two operations:
///
/// - `world_export` (a.k.a. `worlds_export`): zips the world folder into a `.zip` archive, exactly
///   like HMCL's native "Export" ([WorldExportPage] / [WorldManageUIUtils#export]) which calls
///   [World#export(Path, String)] — the archive's single top-level folder is the world's in-game
///   name (falling back to the save-folder name when the world has no LevelName).
/// - `world_duplicate` (a.k.a. `worlds_duplicate`): copies the world into a new save folder under the
///   same `saves/`, exactly like the native "Duplicate" ([WorldManageUIUtils#copyWorld]) which calls
///   [World#copy(String)] — the copy excludes `session.lock` and its in-game LevelName is set to the
///   new folder name.
///
/// This reuses HMCL's native world APIs directly (no re-implemented zip/copy logic):
/// - [`Profiles#getSelectedProfile()`] / [`Profiles#getSelectedInstance()`] for the active profile/instance,
/// - [`HMCLGameRepository#getRunDirectory(String)`] for the isolation-aware run directory, from which
///   `saves/<world>` is resolved (the same path {@link DeleteWorldTool} / {@link ImportWorldTool} use),
/// - [`World#export(Path, String)`] / [`World#copy(String)`] to perform the actual work.
///
/// Both operations are additive and never overwrite: the export refuses a target path that already
/// exists, and the duplicate refuses a destination folder that already exists. Validation mirrors the
/// native UI's own guards — a missing/mistyped world lists the real save folders
/// ({@link WorldToolSupport}), a duplicate's new name is rejected by {@link FileUtils#isNameValid}
/// (the same {@code Validator} the native duplicate prompt uses), and a world locked by a running game
/// blocks the duplicate exactly as {@link World#copy} would.
///
/// Permission level: it WRITES a new file/folder on disk (a new `.zip` / a new `saves/` folder) but is
/// non-destructive — it never deletes or overwrites existing data. It is NOT read-only.
@NotNullByDefault
public final class WorldExportTool implements Tool {

    @Override
    public String getName() {
        return "world_export";
    }

    @Override
    public String getDescription() {
        return "Exports or duplicates a single-player world (the folder saves/<world>). "
                + "The 'action' parameter selects the operation: an export action zips the world into a .zip "
                + "archive; a duplicate action copies the world into a new save folder under the same saves/. "
                + "Parameters: world (required, the save folder name under 'saves/'); "
                + "instance (optional, the instance id; defaults to the currently selected instance); "
                + "target (export only, optional: the output .zip path — absolute, or relative to the instance's "
                + "game directory; a bare directory or a name without '.zip' is completed automatically; defaults "
                + "to '<world>.zip' in the instance's game directory); "
                + "newName (duplicate only, required: the folder name for the copied world; must be a valid folder "
                + "name and must not already exist). "
                + "Never overwrites: export refuses an existing target file, duplicate refuses an existing folder. "
                + "The duplicate refuses a world that is currently open in a running game (locked).";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String action = actionOf(parameters);
        boolean duplicate = action.contains("duplicate") || action.contains("copy") || action.contains("clone");
        boolean export = action.contains("export");
        if (duplicate == export) {
            // Neither matched, or (defensively) both — this handler must be dispatched a clear export
            // OR duplicate action. Prefer failing loudly over guessing.
            return ToolResult.failure("WorldExportTool received an unrecognized action '" + action + "'. "
                    + "Use an export action to zip a world, or a duplicate action to copy it.");
        }

        Profile profile;
        try {
            profile = Profiles.getSelectedProfile();
        } catch (Throwable e) {
            return ToolResult.failure("No profile is currently selected: " + e.getMessage());
        }
        HMCLGameRepository repository = profile.getRepository();

        // No generic aliases: this handler has several distinct string parameters (world / target /
        // newName), so the instance resolver must not steal one of them via its 'query' fallback.
        InstanceToolSupport.ResolvedInstance target =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (target.failure() != null) {
            return target.failure();
        }
        String instance = target.name();

        // 'action' is always present as the dispatch key, and 'target'/'newName'/'confirm' belong to
        // the other parameters — reserve them so a call that omits 'world' can never have one of them
        // grabbed as the world by the generic/sole-value fallback (which produced misleading
        // "world 'X' was not found" errors elsewhere).
        String world = ToolParams.primary(parameters, "world",
                new String[]{"action", "instance", "target", "newName", "confirm"},
                "save", "folder", "saveName");
        if (world.isEmpty()) {
            return ToolResult.failure("Parameter 'world' (the save folder name under 'saves/') is required.");
        }

        Path savesDir;
        Path worldDir;
        try {
            savesDir = repository.getRunDirectory(instance).resolve("saves").normalize();
            worldDir = savesDir.resolve(world).normalize();
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve the run directory of '" + instance + "': " + e.getMessage());
        }

        // Path confinement: a garbled name like "../.." or an absolute path must never escape the
        // saves directory (mirrors DeleteWorldTool's guard).
        if (!worldDir.startsWith(savesDir) || worldDir.equals(savesDir)) {
            return ToolResult.failure("Refused to use '" + world + "': it resolves outside the saves directory. "
                    + "Pass a single world folder name only.");
        }
        if (!Files.isDirectory(worldDir)) {
            return WorldToolSupport.worldNotFoundFailure(savesDir, world);
        }

        World w;
        try {
            w = new World(worldDir);
        } catch (IOException e) {
            return ToolResult.failure("World '" + world + "' of instance '" + instance
                    + "' could not be read as a valid Minecraft world (" + e.getMessage() + ").");
        }

        return export
                ? doExport(parameters, instance, world, savesDir, worldDir, w)
                : doDuplicate(parameters, instance, world, savesDir, w);
    }

    /// Zips the world into a `.zip` via {@link World#export(Path, String)} (the exact call the native
    /// export wizard makes). Refuses to overwrite an existing target and never writes the archive
    /// inside the world folder being zipped.
    private ToolResult doExport(Map<String, Object> parameters, String instance, String world,
                                Path savesDir, Path worldDir, World w) {
        String worldFolderName = String.valueOf(worldDir.getFileName());

        // The single top-level folder inside the zip = the world's in-game name (what the native
        // wizard uses), falling back to the save-folder name when the world has no LevelName so the
        // archive never gets an empty/degenerate top-level entry.
        String worldName;
        try {
            worldName = w.getWorldName();
        } catch (Throwable e) {
            worldName = "";
        }
        String topName = (worldName == null || worldName.isBlank()) ? worldFolderName : worldName;

        Path runDir = savesDir.getParent(); // saves/.. == the instance's isolation-aware game directory

        Path targetZip;
        String targetText = stringAny(parameters, "target", "output", "path", "dest", "destination", "zip", "file", "to");
        if (targetText == null) {
            targetZip = runDir.resolve(worldFolderName + ".zip");
        } else {
            Path p;
            try {
                p = Paths.get(targetText);
            } catch (InvalidPathException e) {
                return ToolResult.failure("Invalid 'target' path '" + targetText + "': " + e.getMessage());
            }
            // A relative target is resolved against the instance's game directory so the result is
            // predictable (the launcher's process working directory would not be).
            if (!p.isAbsolute()) {
                p = runDir.resolve(p);
            }
            if (Files.isDirectory(p)) {
                targetZip = p.resolve(worldFolderName + ".zip");
            } else if (!String.valueOf(p.getFileName()).toLowerCase(Locale.ROOT).endsWith(".zip")) {
                targetZip = p.resolveSibling(p.getFileName() + ".zip");
            } else {
                targetZip = p;
            }
        }
        targetZip = targetZip.normalize();

        // Never write the archive inside the folder being zipped (it would try to include itself).
        if (targetZip.startsWith(worldDir)) {
            return ToolResult.failure("Refused to export: the target path is inside the world folder being "
                    + "exported. Choose a location outside '" + worldDir + "'.");
        }
        if (Files.exists(targetZip)) {
            return ToolResult.failure("A file already exists at the target path: " + targetZip
                    + ". Choose a different 'target' — this tool never overwrites an existing file.");
        }
        Path parent = targetZip.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                return ToolResult.failure("Failed to create the target directory '" + parent + "': " + e.getMessage());
            }
        }

        try {
            w.export(targetZip, topName);
        } catch (Throwable e) {
            // Clean up a partially-written archive so a retry isn't blocked by our own leftover.
            try {
                Files.deleteIfExists(targetZip);
            } catch (IOException ignored) {
                // best effort
            }
            return ToolResult.failure("Failed to export world '" + world + "' of instance '" + instance
                    + "': " + e.getMessage());
        }

        long size = -1L;
        try {
            size = Files.size(targetZip);
        } catch (IOException ignored) {
            // size is best-effort
        }

        String lockNote = "";
        try {
            if (w.isLocked()) {
                lockNote = "\nNote: the world is currently in use (locked by a running game), so the exported "
                        + "snapshot may be slightly inconsistent.";
            }
        } catch (Throwable ignored) {
            // lock probing is best-effort
        }

        return ToolResult.success("Exported world '" + world + "' of instance '" + instance + "' to a .zip archive.\n"
                + "Archive: " + targetZip + (size >= 0 ? " (" + size + " bytes)" : "") + "\n"
                + "Top-level folder inside the zip: " + topName + lockNote);
    }

    /// Copies the world into a new `saves/` folder via {@link World#copy(String)} (the exact call the
    /// native duplicate makes). Validates the new name with {@link FileUtils#isNameValid} (the same
    /// check the native prompt's {@code Validator} uses), refuses an existing destination, and refuses
    /// a world locked by a running game.
    private ToolResult doDuplicate(Map<String, Object> parameters, String instance, String world,
                                   Path savesDir, World w) {
        String newName = stringAny(parameters, "newName", "copyName", "newWorld", "destName", "to");
        if (newName == null) {
            return ToolResult.failure("Parameter 'newName' (the folder name for the copied world) is required.");
        }
        if (!FileUtils.isNameValid(newName)) {
            return ToolResult.failure("The new world name '" + newName + "' is not a valid folder name on this "
                    + "system. Use a name without path separators or characters illegal in a folder name.");
        }

        Path newPath;
        try {
            newPath = savesDir.resolve(newName).normalize();
        } catch (InvalidPathException e) {
            return ToolResult.failure("Invalid new world name '" + newName + "': " + e.getMessage());
        }
        // isNameValid already forbids separators, so this is a belt-and-suspenders confinement check:
        // the copy must be a single folder directly under saves/.
        if (newPath.equals(savesDir) || !savesDir.equals(newPath.getParent())) {
            return ToolResult.failure("Refused to duplicate: '" + newName + "' does not resolve to a single "
                    + "folder directly under saves/. Pass a bare folder name.");
        }
        if (Files.exists(newPath)) {
            return ToolResult.failure("A world folder named '" + newName + "' already exists: " + newPath
                    + ". Choose a different name.");
        }

        // Pre-check the lock for a clear message; World#copy also guards this and throws
        // WorldLockedException, which we translate below as a backstop.
        try {
            if (w.isLocked()) {
                return ToolResult.failure("World '" + world + "' of instance '" + instance + "' is currently open "
                        + "in a running game (its session.lock is held) — close the world/game first, then retry.");
            }
        } catch (Throwable ignored) {
            // lock probing is best-effort; World#copy still guards the real operation
        }

        try {
            w.copy(newName);
        } catch (WorldLockedException e) {
            return ToolResult.failure("World '" + world + "' of instance '" + instance + "' is currently in use "
                    + "(locked by a running game); close the world/game first, then retry.");
        } catch (Throwable e) {
            // Clean up a partial copy so a retry isn't blocked by our own leftover.
            try {
                if (Files.isDirectory(newPath)) {
                    FileUtils.forceDelete(newPath);
                }
            } catch (Throwable ignored) {
                // best effort
            }
            return ToolResult.failure("Failed to duplicate world '" + world + "' of instance '" + instance
                    + "': " + e.getMessage());
        }

        long[] stats = tally(newPath);
        return ToolResult.success("Duplicated world '" + world + "' of instance '" + instance + "' as '" + newName + "'.\n"
                + "New world folder: " + newPath + "\n"
                + "Copied: " + stats[0] + " files (" + stats[1] + " bytes). The copy's in-game name was set to '"
                + newName + "'.");
    }

    /// Best-effort tally of {@code [fileCount, totalBytes]} under a directory, for a concrete report.
    private static long[] tally(Path dir) {
        long[] stats = new long[2];
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(path)) {
                    stats[0]++;
                    try {
                        stats[1] += Files.size(path);
                    } catch (Throwable ignored) {
                        // size is best-effort
                    }
                }
            }
        } catch (Throwable ignored) {
            // the copy already succeeded; a failed tally must not turn success into an error
        }
        return stats;
    }

    /// Returns the first non-blank trimmed value among {@code keys}, or {@code null} if none is
    /// present. Used for this tool's secondary string parameters (target / newName), which — unlike a
    /// primary parameter — must NOT fall back to generic dump-keys.
    @Nullable
    private static String stringAny(Map<String, Object> parameters, String... keys) {
        for (String key : keys) {
            String value = InstanceToolSupport.string(parameters, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String actionOf(Map<String, Object> parameters) {
        Object action = parameters.get("action");
        return action != null ? action.toString().trim().toLowerCase(Locale.ROOT) : "";
    }
}
