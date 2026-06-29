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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/// A tool that backs up a single Minecraft world (save) of an instance by
/// recursively copying its `saves/<world>` directory into a timestamped folder
/// under `<runDir>/saves-backups/`.
///
/// This reuses HMCL's launcher APIs directly:
/// - [`Profiles#getSelectedInstance()`] for the default instance id,
/// - [`Profile#getRepository()`] / [`HMCLGameRepository#getRunDirectory(String)`]
///   for the isolation-aware per-instance run directory.
///
/// Permission level: it only READS the source world directory and WRITES a new
/// backup directory; it never modifies the original save.
@NotNullByDefault
public final class BackupWorldTool implements Tool {

    @Override
    public String getName() {
        return "backup_world";
    }

    @Override
    public String getDescription() {
        return "Backs up a single Minecraft world (save) by recursively copying its folder. "
                + "Parameters: world (required, the save folder name under 'saves/'), "
                + "instance (optional, the instance id; defaults to the currently selected instance). "
                + "The world is copied into '<runDir>/saves-backups/<world>-<timestamp>/'. "
                + "Only reads the source world and writes a new backup directory; the original save is never modified.";
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

        Path runDirectory;
        try {
            runDirectory = repository.getRunDirectory(instance);
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve the run directory of '" + instance + "': " + e.getMessage());
        }

        Path source = runDirectory.resolve("saves").resolve(world);
        if (!Files.isDirectory(source)) {
            return ToolResult.failure("World '" + world + "' was not found at: " + source);
        }

        Path target = runDirectory.resolve("saves-backups").resolve(world + "-" + System.currentTimeMillis());

        long[] counters = new long[2]; // [0] = files copied, [1] = directories created
        try {
            Files.createDirectories(target);
            try (Stream<Path> walk = Files.walk(source)) {
                for (Path path : (Iterable<Path>) walk::iterator) {
                    Path relative = source.relativize(path);
                    Path destination = target.resolve(relative.toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                        counters[1]++;
                    } else {
                        Path parent = destination.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.copy(path, destination);
                        counters[0]++;
                    }
                }
            }
        } catch (Throwable e) {
            return ToolResult.failure("Failed to back up world '" + world + "': " + e.getMessage());
        }

        return ToolResult.success("Backed up world '" + world + "' of instance '" + instance + "'.\n"
                + "Backup path: " + target + "\n"
                + "Files copied: " + counters[0] + " (directories: " + counters[1] + ")");
    }
}
