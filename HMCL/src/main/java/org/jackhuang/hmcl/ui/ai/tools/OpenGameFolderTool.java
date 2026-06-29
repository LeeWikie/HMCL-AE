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
import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/// A tool that opens a Minecraft instance directory in the operating system's
/// native file manager.
///
/// It reuses HMCL's own folder-opening API, [`FXUtils#openFolder(Path)`], which
/// already creates the directory if it is missing, picks the correct platform
/// command (explorer.exe / open / xdg-open) and runs it on a background thread
/// (so it is safe to call from any thread, no JavaFX thread hop required).
///
/// It resolves instance paths via:
/// - [`Profiles#getSelectedProfile()`] / [`Profiles#getSelectedInstance()`],
/// - [`HMCLGameRepository#getRunDirectory(String)`] for the run directory.
///
/// Permission level: side-effecting. It may create the directory (via
/// [`FXUtils#openFolder(Path)`]) and launches an external file-manager process,
/// but it does not modify any game files.
@NotNullByDefault
public final class OpenGameFolderTool implements Tool {

    @Override
    public String getName() {
        return "open_game_folder";
    }

    @Override
    public String getDescription() {
        return "Opens a Minecraft instance directory in the system file manager. "
                + "Parameters: 'instance' (optional, the instance/version id; defaults to the currently selected instance), "
                + "'folder' (optional, one of: root (default), mods, saves, config, resourcepacks, screenshots, logs; relative to the run directory). "
                + "The directory is created if it does not yet exist, then opened with the OS file explorer. "
                + "Returns the path that was opened.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        // Resolve the folder sub-path.
        Object folderObj = parameters.get("folder");
        String folder = folderObj != null && !folderObj.toString().trim().isEmpty()
                ? folderObj.toString().trim().toLowerCase(Locale.ROOT)
                : "root";

        String subPath;
        switch (folder) {
            case "root":
                subPath = "";
                break;
            case "mods":
            case "saves":
            case "config":
            case "resourcepacks":
            case "screenshots":
            case "logs":
                subPath = folder;
                break;
            default:
                return ToolResult.failure("Unknown 'folder' value: '" + folder + "'. "
                        + "Allowed values: root, mods, saves, config, resourcepacks, screenshots, logs.");
        }

        // Resolve the instance.
        Profile profile;
        try {
            profile = Profiles.getSelectedProfile();
        } catch (Throwable e) {
            return ToolResult.failure("No profile is currently selected: " + e.getMessage());
        }
        HMCLGameRepository repository = profile.getRepository();

        Object instanceObj = parameters.get("instance");
        String instanceId = instanceObj != null && !instanceObj.toString().trim().isEmpty()
                ? instanceObj.toString().trim()
                : Profiles.getSelectedInstance();
        if (instanceId == null || instanceId.isEmpty()) {
            return ToolResult.failure("No instance specified and no instance is currently selected.");
        }

        if (!repository.isLoaded()) {
            try {
                repository.refreshVersions();
            } catch (Throwable e) {
                return ToolResult.failure("Failed to load installed instances: " + e.getMessage());
            }
        }
        if (!repository.hasVersion(instanceId)) {
            return ToolResult.failure("Instance '" + instanceId + "' does not exist in the selected profile.");
        }

        Path target;
        try {
            Path runDirectory = repository.getRunDirectory(instanceId);
            target = subPath.isEmpty() ? runDirectory : runDirectory.resolve(subPath);
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve directory: " + e.getMessage());
        }

        // Ensure the directory exists (FXUtils.openFolder also does this, but do it
        // explicitly so we can report a clear error if it cannot be created).
        try {
            Files.createDirectories(target);
        } catch (Throwable e) {
            return ToolResult.failure("Failed to create directory " + target + ": " + e.getMessage());
        }

        try {
            // FXUtils.openFolder runs the actual open command on a background thread,
            // so it is safe to call directly from here.
            FXUtils.openFolder(target);
        } catch (Throwable e) {
            return ToolResult.failure("Failed to open directory " + target + ": " + e.getMessage());
        }

        return ToolResult.success("Opened folder in the system file manager:\n  " + target);
    }
}
