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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/// A read-only tool that lists the datapacks installed in a single-player world,
/// i.e. the entries (zip files or sub-folders) under `saves/<world>/datapacks/`.
///
/// This reuses HMCL's launcher APIs directly:
/// - [`Profiles#getSelectedProfile()`] / [`Profiles#getSelectedInstance()`] for the active profile/instance,
/// - [`HMCLGameRepository#hasVersion(String)`] to validate the instance,
/// - [`HMCLGameRepository#getRunDirectory(String)`] for the isolation-aware run directory,
///   from which `saves/<world>/datapacks` is resolved.
///
/// Permission level: READ_ONLY. It never modifies any save.
@NotNullByDefault
public final class ListDatapacksTool implements Tool {

    @Override
    public String getName() {
        return "list_datapacks";
    }

    @Override
    public String getDescription() {
        return "Lists the datapacks installed in a single-player world, i.e. the zip files and sub-folders under "
                + "saves/<world>/datapacks/. "
                + "Parameters: world (required, the save folder name under 'saves/'), "
                + "instance (optional, the instance id; defaults to the currently selected instance). "
                + "Read-only. Use this instead of ls/dir over the datapacks folder. "
                + "To add a datapack use instance(action=\"worlds_datapacks_install\").";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        final String world = ToolParams.primary(parameters, "world",
                new String[]{"instance"}, "save", "folder", "saveName", "name");
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

        // NOTE: generic aliases (name/query/version) are intentionally NOT accepted here:
        // the primary 'world' parameter above already claims the 'name' alias and the generic
        // 'query' fallback, so the instance resolver must not steal those values.
        InstanceToolSupport.ResolvedInstance target =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (target.failure() != null) {
            return target.failure();
        }
        String instance = target.name();

        Path savesDir;
        Path worldDir;
        try {
            savesDir = repository.getRunDirectory(instance).resolve("saves");
            worldDir = savesDir.resolve(world);
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve the run directory of '" + instance + "': " + e.getMessage());
        }

        if (!Files.isDirectory(worldDir)) {
            // Same "not found → list the real candidates" pattern InstanceToolSupport uses for a
            // missing instance, now for a missing world (lists the real saves/ folder names).
            return WorldToolSupport.worldNotFoundFailure(savesDir, world);
        }

        Path datapacksDir = worldDir.resolve("datapacks");
        if (!Files.isDirectory(datapacksDir)) {
            return ToolResult.success("World '" + world + "' of instance '" + instance + "' has no 'datapacks' folder yet "
                    + "(no datapacks). Expected at: " + datapacksDir);
        }

        List<Path> entries = new ArrayList<>();
        try (Stream<Path> list = Files.list(datapacksDir)) {
            for (Path path : (Iterable<Path>) list::iterator) {
                entries.add(path);
            }
        } catch (Throwable e) {
            return ToolResult.failure("Failed to read datapacks of world '" + world + "': " + e.getMessage());
        }

        if (entries.isEmpty()) {
            return ToolResult.success("No datapacks found in " + datapacksDir + ".");
        }

        entries.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));

        StringBuilder sb = new StringBuilder();
        sb.append("Datapacks of world '").append(world).append("' (instance '").append(instance).append("') (")
                .append(entries.size()).append("):\n");
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            boolean directory = Files.isDirectory(entry);
            sb.append("  - ").append(name);
            if (directory) {
                sb.append("  [folder]");
            } else {
                sb.append("  [zip/file");
                try {
                    sb.append(", ").append(Files.size(entry)).append(" bytes");
                } catch (Throwable ignored) {
                    // size is best-effort
                }
                sb.append(']');
            }
            sb.append('\n');
        }

        return ToolResult.success(sb.toString().trim());
    }
}
