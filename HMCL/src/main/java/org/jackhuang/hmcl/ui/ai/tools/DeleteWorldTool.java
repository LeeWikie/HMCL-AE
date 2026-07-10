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
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/// DANGEROUS / IRREVERSIBLE: permanently deletes a single-player world directory
/// (`saves/<world>`) from disk.
///
/// This reuses HMCL's launcher APIs directly:
/// - [`Profiles#getSelectedProfile()`] / [`Profiles#getSelectedInstance()`] for the active profile/instance,
/// - [`HMCLGameRepository#hasVersion(String)`] to validate the instance,
/// - [`HMCLGameRepository#getRunDirectory(String)`] for the isolation-aware run directory,
///   from which `saves/<world>` is resolved.
///
/// As a safety net (mirroring {@link DeleteInstanceTool}, until a real UI confirmation dialog
/// is wired into the upper-layer confirm mechanism), this tool requires an explicit
/// {@code confirm=true} parameter. Without it nothing is deleted and the tool reports the exact
/// path, file count and total size that would be removed, then explains how to confirm.
@NotNullByDefault
public final class DeleteWorldTool implements Tool {

    private final java.util.function.BooleanSupplier toRecycleBin;

    /// @param toRecycleBin whether to route deletions to the OS recycle bin (recoverable) instead
    ///                     of permanently deleting; read live on each call.
    public DeleteWorldTool(java.util.function.BooleanSupplier toRecycleBin) {
        this.toRecycleBin = toRecycleBin;
    }

    @Override
    public String getName() {
        return "delete_world";
    }

    @Override
    public String getDescription() {
        return "DANGEROUS / IRREVERSIBLE: permanently DELETE a single-player world (the folder saves/<world>) from disk. "
                + "This cannot be undone. "
                + "Parameters: world (required, the save folder name under 'saves/'), "
                + "instance (optional, the instance id; defaults to the currently selected instance), "
                + "confirm (REQUIRED boolean). "
                + "If confirm is not exactly true, NOTHING is deleted and the tool reports the path, file count and "
                + "total size that WOULD be removed; you must then re-invoke with confirm=true to actually delete. "
                + "Only use this when the user has clearly asked to delete that specific world. "
                + "Consider instance(action=\"worlds_backup_create\") first.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String world = ToolParams.primary(parameters, "world",
                new String[]{"confirm", "instance"}, "save", "folder", "saveName", "name");
        if (world.isEmpty()) {
            return ToolResult.failure("Parameter 'world' (the save folder name) is required.");
        }

        Profile profile;
        try {
            profile = Profiles.getSelectedProfile();
        } catch (Throwable e) {
            return ToolResult.failure("No profile is currently selected: " + e.getMessage());
        }
        HMCLGameRepository repository = profile.getRepository();

        InstanceToolSupport.ResolvedInstance target =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (target.failure() != null) {
            return target.failure();
        }
        String instance = target.name();

        Path worldDir;
        Path savesDir;
        try {
            savesDir = repository.getRunDirectory(instance).resolve("saves").normalize();
            worldDir = savesDir.resolve(world).normalize();
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve the run directory of '" + instance + "': " + e.getMessage());
        }

        // Path confinement: a malicious/garbled name like "../.." or an absolute path must never
        // escape the saves directory — otherwise delete_world could remove arbitrary folders.
        if (!worldDir.startsWith(savesDir) || worldDir.equals(savesDir)) {
            return ToolResult.failure("Refused to delete '" + world + "': it resolves outside the saves directory. "
                    + "Pass a single world folder name only.");
        }

        if (!Files.isDirectory(worldDir)) {
            // Same "not found → list the real candidates" pattern InstanceToolSupport uses for a
            // missing instance, now for a missing world (lists the real saves/ folder names).
            return WorldToolSupport.worldNotFoundFailure(savesDir, world);
        }

        // Best-effort lock check, mirroring World#delete()'s own guard (World.java) — a running
        // game instance holds the world's session.lock, and deleting/trashing it out from under a
        // live session should be refused just like the sibling World.delete() already refuses via
        // WorldLockedException. A directory that isn't a well-formed World (e.g. legacy/corrupt,
        // missing level.dat) skips this check rather than blocking a deletion the user explicitly
        // asked for.
        try {
            org.jackhuang.hmcl.game.World w = new org.jackhuang.hmcl.game.World(worldDir);
            if (w.isLocked()) {
                return ToolResult.failure("World '" + world + "' of instance '" + instance
                        + "' is currently open in a running game instance (its session.lock is held) — "
                        + "close the world/game first, then retry.");
            }
        } catch (IOException ignored) {
            // Not a well-formed World — proceed; there is nothing lock-related to protect.
        }

        // Tally what would be / is being deleted, so the report is concrete.
        long[] stats = new long[2]; // [0] = file count, [1] = total bytes
        try (Stream<Path> walk = Files.walk(worldDir)) {
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
        } catch (Throwable e) {
            return ToolResult.failure("Failed to inspect world '" + world + "': " + e.getMessage());
        }

        boolean confirmed = parameters.get("confirm") instanceof Boolean b
                ? b
                : parameters.get("confirm") != null && "true".equalsIgnoreCase(parameters.get("confirm").toString().trim());

        if (!confirmed) {
            return ToolResult.failure("Not confirmed: this will PERMANENTLY DELETE the world '" + world
                    + "' of instance '" + instance + "' and all of its files. This action is IRREVERSIBLE.\n"
                    + "Path: " + worldDir + "\n"
                    + "Files: " + stats[0] + " (total " + stats[1] + " bytes)\n"
                    + "Re-invoke instance(action=\"worlds_delete\", world=\"" + world + "\", confirm=true) to proceed "
                    + "(consider instance(action=\"worlds_backup_create\", world=\"" + world + "\") first).");
        }

        boolean trashed;
        try {
            trashed = FileTrash.delete(worldDir, toRecycleBin.getAsBoolean());
        } catch (Throwable e) {
            return ToolResult.failure("Failed to delete world '" + world + "': " + e.getMessage());
        }

        return ToolResult.success((trashed
                ? "Moved world '" + world + "' of instance '" + instance + "' to the system recycle bin (recoverable).\n"
                : "Permanently deleted world '" + world + "' of instance '" + instance + "' from disk.\n")
                + "Path: " + worldDir + "\n"
                + "Files: " + stats[0] + " (total " + stats[1] + " bytes)");
    }
}
