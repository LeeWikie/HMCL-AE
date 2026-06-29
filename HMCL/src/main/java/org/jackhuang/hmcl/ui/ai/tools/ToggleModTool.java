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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// A tool that enables or disables a mod of a Minecraft instance by renaming the
/// mod file inside its `mods` directory.
///
/// In HMCL (as in most launchers) a mod is considered enabled when its file name
/// ends with `.jar`, and disabled when the `.disabled` suffix is appended
/// (`xxx.jar.disabled`). This tool toggles that suffix using [Files#move].
///
/// It reuses HMCL's launcher APIs directly:
/// - [`Profiles#getSelectedProfile()`] / [`Profiles#getSelectedInstance()`],
/// - [`HMCLGameRepository#getRunDirectory(String)`] to locate the `mods` folder.
///
/// Permission level: MODIFIES the file system (renames a file on disk).
@NotNullByDefault
public final class ToggleModTool implements Tool {

    private static final String DISABLED_SUFFIX = ".disabled";

    @Override
    public String getName() {
        return "toggle_mod";
    }

    @Override
    public String getDescription() {
        return "Enables or disables a single mod of a Minecraft instance by renaming its file in the mods directory "
                + "(enabled = 'xxx.jar', disabled = 'xxx.jar.disabled'). "
                + "Parameters: 'mod' (required, the mod file name or a case-insensitive substring that matches exactly one file), "
                + "'instance' (optional, the instance/version id; defaults to the currently selected instance), "
                + "'enable' (optional boolean; if provided forces enable=true or disable=false, otherwise the current state is toggled). "
                + "WARNING: this modifies the file system by renaming a file on disk. "
                + "Returns the old name, the new name and the resulting state. "
                + "Fails if the substring matches zero or more than one file.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object modObj = parameters.get("mod");
        if (modObj == null || modObj.toString().trim().isEmpty()) {
            return ToolResult.failure("Missing required parameter 'mod' (the mod file name or a substring of it).");
        }
        String modQuery = modObj.toString().trim();

        // Resolve the optional 'enable' parameter (may be a Boolean or a String like "true"/"false").
        Boolean forceEnable = null;
        Object enableObj = parameters.get("enable");
        if (enableObj != null) {
            if (enableObj instanceof Boolean b) {
                forceEnable = b;
            } else {
                String s = enableObj.toString().trim().toLowerCase(Locale.ROOT);
                if (s.equals("true")) {
                    forceEnable = Boolean.TRUE;
                } else if (s.equals("false")) {
                    forceEnable = Boolean.FALSE;
                } else if (!s.isEmpty()) {
                    return ToolResult.failure("Parameter 'enable' must be a boolean (true/false), got: " + enableObj);
                }
            }
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

        Path modsDir;
        try {
            modsDir = repository.getRunDirectory(instanceId).resolve("mods");
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve mods directory: " + e.getMessage());
        }
        if (!Files.isDirectory(modsDir)) {
            return ToolResult.failure("Mods directory does not exist for instance '" + instanceId + "': " + modsDir);
        }

        // Collect candidate mod files (only regular files) and match by case-insensitive substring.
        List<Path> candidates = new ArrayList<>();
        String queryLower = modQuery.toLowerCase(Locale.ROOT);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                if (name.toLowerCase(Locale.ROOT).contains(queryLower)) {
                    candidates.add(entry);
                }
            }
        } catch (IOException e) {
            return ToolResult.failure("Failed to list mods directory: " + e.getMessage());
        }

        if (candidates.isEmpty()) {
            return ToolResult.failure("No mod file matching '" + modQuery + "' was found in " + modsDir);
        }
        if (candidates.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("Ambiguous: '").append(modQuery).append("' matches ").append(candidates.size()).append(" files:\n");
            for (Path c : candidates) {
                sb.append("  - ").append(c.getFileName()).append('\n');
            }
            sb.append("Please refine the 'mod' parameter to match exactly one file.");
            return ToolResult.failure(sb.toString().trim());
        }

        Path source = candidates.get(0);
        String oldName = source.getFileName().toString();
        boolean currentlyEnabled = !oldName.toLowerCase(Locale.ROOT).endsWith(DISABLED_SUFFIX);

        boolean targetEnabled = forceEnable != null ? forceEnable : !currentlyEnabled;

        if (targetEnabled == currentlyEnabled) {
            return ToolResult.success("Mod '" + oldName + "' is already "
                    + (currentlyEnabled ? "enabled" : "disabled") + "; no change made.\n"
                    + "Path: " + source);
        }

        String newName;
        if (targetEnabled) {
            // Remove the trailing .disabled suffix.
            newName = oldName.substring(0, oldName.length() - DISABLED_SUFFIX.length());
        } else {
            newName = oldName + DISABLED_SUFFIX;
        }

        Path target = source.resolveSibling(newName);
        if (Files.exists(target)) {
            return ToolResult.failure("Cannot rename '" + oldName + "' to '" + newName + "': target already exists.");
        }

        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable atomicFailure) {
            // ATOMIC_MOVE may not be supported on every file system; fall back to a plain move.
            try {
                Files.move(source, target);
            } catch (Throwable e) {
                return ToolResult.failure("Failed to rename '" + oldName + "' to '" + newName + "': " + e.getMessage());
            }
        }

        return ToolResult.success("Mod " + (targetEnabled ? "enabled" : "disabled") + " in instance '" + instanceId + "'.\n"
                + "  " + oldName + "  ->  " + newName + "\n"
                + "  state: " + (targetEnabled ? "enabled" : "disabled") + "\n"
                + "  path : " + target);
    }
}
