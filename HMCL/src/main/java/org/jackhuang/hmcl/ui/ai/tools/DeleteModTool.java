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

import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/// A controlled-write tool that removes a single mod file from an instance's
/// `mods` directory.
///
/// Resolution mirrors {@link ToggleModTool}: the `mods` folder is located through
/// HMCL's launcher APIs ([`Profiles#getSelectedProfile()`] /
/// [`Profiles#getSelectedInstance()`] / [`HMCLGameRepository#getRunDirectory(String)`]),
/// then every regular file in it is matched against the `mod` parameter by a
/// case-insensitive substring. The tool refuses to act unless exactly one file
/// matches (zero or several is an error), so it can never delete the wrong mod by
/// accident. Both enabled (`xxx.jar`) and disabled (`xxx.jar.disabled`) files are
/// considered.
///
/// Deletion is recoverable when possible: the file is routed to the OS recycle
/// bin / trash via {@link FileTrash} when the user's "delete to recycle bin"
/// preference (supplied to the constructor and read live on every call) is enabled
/// and the platform supports it; otherwise it is permanently deleted.
///
/// Permission level: {@link ToolPermission#CONTROLLED_WRITE}. It removes exactly
/// one mod file and never touches anything else.
@NotNullByDefault
public final class DeleteModTool implements ToolSpec {

    private static final String DISABLED_SUFFIX = ".disabled";

    /// Whether to route the deletion to the OS recycle bin (recoverable) instead of
    /// permanently deleting it; read live on each call.
    private final BooleanSupplier toRecycleBin;

    /// @param toRecycleBin whether to prefer the OS recycle bin (recoverable) over a
    ///                     permanent delete; typically `aiSettings::isDeleteToRecycleBin`.
    public DeleteModTool(BooleanSupplier toRecycleBin) {
        this.toRecycleBin = toRecycleBin;
    }

    @Override
    public String getName() {
        return "delete_mod";
    }

    @Override
    public ToolPermission getPermission() {
        return ToolPermission.CONTROLLED_WRITE;
    }

    @Override
    public boolean supportsStructuredSchema() {
        return true;
    }

    @Override
    public String getInputSchemaJson() {
        return """
               {
                 "$schema": "https://json-schema.org/draft/2020-12/schema",
                 "type": "object",
                 "properties": {
                   "mod": {"type": "string", "description": "The mod file name, or a case-insensitive substring of it that matches exactly one file in the mods folder (matches both enabled .jar and disabled .jar.disabled files)."},
                   "instance": {"type": "string", "description": "Optional instance/version id; defaults to the currently selected instance."}
                 },
                 "required": ["mod"]
               }
               """;
    }

    @Override
    public String getDescription() {
        return "Removes a single mod from an instance's mods folder (use this instead of a raw file delete). "
                + "Parameters: mod (required, the mod file name or a case-insensitive substring that matches exactly one file; "
                + "matches both enabled '.jar' and disabled '.jar.disabled' files), "
                + "instance (optional, the instance/version id; defaults to the currently selected instance). "
                + "The file is moved to the system recycle bin when possible (recoverable), otherwise permanently deleted. "
                + "Fails if the substring matches zero or more than one file, so it never deletes the wrong mod. "
                + "Returns the removed file name and whether it was recycled or permanently deleted.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object modObj = parameters.get("mod");
        if (modObj == null || modObj.toString().trim().isEmpty()) {
            return ToolResult.failure("Missing required parameter 'mod' (the mod file name or a substring of it).");
        }
        String modQuery = modObj.toString().trim();

        Profile profile;
        try {
            profile = Profiles.getSelectedProfile();
        } catch (Throwable e) {
            return ToolResult.failure("No profile is currently selected: " + e.getMessage());
        }
        HMCLGameRepository repository = profile.getRepository();

        Object instanceObj = parameters.get("instance");
        String instanceId;
        if (instanceObj != null && !instanceObj.toString().trim().isEmpty()) {
            instanceId = instanceObj.toString().trim();
        } else {
            @Nullable String selected = Profiles.getSelectedInstance();
            if (selected == null || selected.isEmpty()) {
                return ToolResult.failure("No instance specified and no instance is currently selected.");
            }
            instanceId = selected;
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

        // Collect candidate mod files (only top-level regular files) and match by
        // case-insensitive substring, exactly like ToggleModTool.
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
            return ToolResult.failure("No mod file matching '" + modQuery + "' was found in " + modsDir
                    + ". Use list_mods to see the installed mods.");
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

        Path target = candidates.get(0);
        String fileName = target.getFileName().toString();
        boolean wasDisabled = fileName.toLowerCase(Locale.ROOT).endsWith(DISABLED_SUFFIX);

        boolean recycled;
        try {
            recycled = FileTrash.delete(target, toRecycleBin.getAsBoolean());
        } catch (Throwable e) {
            return ToolResult.failure("Failed to delete mod '" + fileName + "': " + e.getMessage());
        }

        return ToolResult.success((recycled
                ? "Moved mod '" + fileName + "' to the system recycle bin (recoverable).\n"
                : "Permanently deleted mod '" + fileName + "' from disk.\n")
                + "  instance: " + instanceId + "\n"
                + "  was     : " + (wasDisabled ? "disabled" : "enabled") + "\n"
                + "  path    : " + target);
    }
}
