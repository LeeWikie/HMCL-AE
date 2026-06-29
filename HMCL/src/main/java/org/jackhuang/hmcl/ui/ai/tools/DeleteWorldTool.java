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
import org.jackhuang.hmcl.ai.tools.ToolResult;
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
                + "Consider backup_world first.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object worldObj = parameters.get("world");
        if (!(worldObj instanceof String) || ((String) worldObj).trim().isEmpty()) {
            return ToolResult.failure("Parameter 'world' (the save folder name) is required.");
        }
        String world = ((String) worldObj).trim();

        Profile profile;
        try {
            profile = Profiles.getSelectedProfile();
        } catch (Throwable e) {
            return ToolResult.failure("No profile is currently selected: " + e.getMessage());
        }
        HMCLGameRepository repository = profile.getRepository();

        Object instanceObj = parameters.get("instance");
        String instance;
        if (instanceObj instanceof String && !((String) instanceObj).trim().isEmpty()) {
            instance = ((String) instanceObj).trim();
        } else {
            @Nullable String selected = Profiles.getSelectedInstance();
            if (selected == null) {
                return ToolResult.failure("No instance is selected and no 'instance' parameter was given.");
            }
            instance = selected;
        }

        try {
            if (!repository.hasVersion(instance)) {
                return ToolResult.failure("Instance '" + instance + "' does not exist in the selected profile.");
            }
        } catch (Throwable e) {
            return ToolResult.failure("Failed to verify instance '" + instance + "': " + e.getMessage());
        }

        Path worldDir;
        try {
            worldDir = repository.getRunDirectory(instance).resolve("saves").resolve(world);
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve the run directory of '" + instance + "': " + e.getMessage());
        }

        if (!Files.isDirectory(worldDir)) {
            return ToolResult.failure("World '" + world + "' was not found at: " + worldDir);
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
                    + "Re-invoke delete_world with confirm=true to proceed (consider backup_world first).");
        }

        try {
            deleteRecursively(worldDir);
        } catch (Throwable e) {
            return ToolResult.failure("Failed to delete world '" + world + "': " + e.getMessage());
        }

        return ToolResult.success("Permanently deleted world '" + world + "' of instance '" + instance + "' from disk.\n"
                + "Removed path: " + worldDir + "\n"
                + "Files removed: " + stats[0] + " (total " + stats[1] + " bytes)");
    }

    private static void deleteRecursively(Path root) throws IOException {
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
}
