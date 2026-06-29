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
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;

/// A tool that installs a local datapack zip into a single-player world by copying
/// the source file into `saves/<world>/datapacks/`.
///
/// This reuses HMCL's launcher APIs directly:
/// - [`Profiles#getSelectedProfile()`] / [`Profiles#getSelectedInstance()`] for the active profile/instance,
/// - [`HMCLGameRepository#hasVersion(String)`] to validate the instance,
/// - [`HMCLGameRepository#getRunDirectory(String)`] for the isolation-aware run directory,
///   from which `saves/<world>/datapacks` is resolved.
///
/// Permission level: it READS the local source zip and WRITES a copy into the world's
/// datapacks folder. It does not extract or modify the world contents.
@NotNullByDefault
public final class InstallDatapackTool implements Tool {

    @Override
    public String getName() {
        return "install_datapack";
    }

    @Override
    public String getDescription() {
        return "Installs a local datapack ZIP into a single-player world by copying it into saves/<world>/datapacks/. "
                + "Parameters: world (required, the save folder name under 'saves/'), "
                + "source (required, the absolute local path of the .zip datapack to copy), "
                + "instance (optional, the instance id; defaults to the currently selected instance). "
                + "Writes a copy of the source zip into the datapacks folder; it never extracts or edits the world. "
                + "The source must be an existing local .zip file.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object worldObj = parameters.get("world");
        if (!(worldObj instanceof String) || ((String) worldObj).trim().isEmpty()) {
            return ToolResult.failure("Parameter 'world' (the save folder name) is required.");
        }
        String world = ((String) worldObj).trim();

        Object sourceObj = parameters.get("source");
        if (!(sourceObj instanceof String) || ((String) sourceObj).trim().isEmpty()) {
            return ToolResult.failure("Parameter 'source' (the local path of the .zip datapack) is required.");
        }
        String sourceText = ((String) sourceObj).trim();

        Path source;
        try {
            source = Paths.get(sourceText);
        } catch (Throwable e) {
            return ToolResult.failure("Invalid 'source' path '" + sourceText + "': " + e.getMessage());
        }
        if (!Files.isRegularFile(source)) {
            return ToolResult.failure("Source datapack was not found (or is not a regular file): " + source);
        }
        String fileName = source.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return ToolResult.failure("Source datapack must be a .zip file, but got: " + fileName);
        }

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

        Path datapacksDir = worldDir.resolve("datapacks");
        Path destination = datapacksDir.resolve(fileName);
        if (Files.exists(destination)) {
            return ToolResult.failure("A datapack named '" + fileName + "' already exists in this world: " + destination
                    + ". Remove or rename it first.");
        }

        long size;
        try {
            Files.createDirectories(datapacksDir);
            Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
            size = Files.size(destination);
        } catch (Throwable e) {
            return ToolResult.failure("Failed to install datapack '" + fileName + "': " + e.getMessage());
        }

        return ToolResult.success("Installed datapack '" + fileName + "' into world '" + world
                + "' of instance '" + instance + "'.\n"
                + "Path: " + destination + "\n"
                + "Size: " + size + " bytes");
    }
}
