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

import org.jackhuang.hmcl.addon.mod.LocalModFile;
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BooleanSupplier;

/// A controlled-write tool that removes a single mod file from an instance's
/// `mods` directory.
///
/// Resolution goes through the native mod state machine, exactly like {@link ToggleModTool}:
/// the installed mods are parsed via [`HMCLGameRepository#getModManager(String)`] +
/// [`ModManager#getLocalFiles()`] and matched against the `mod` parameter by a case-insensitive
/// substring over the on-disk file name, the display name and the mod id. The tool refuses to
/// act unless exactly one mod matches (zero or several is an error), so it can never delete the
/// wrong mod by accident. Both enabled (`xxx.jar`) and disabled (`xxx.jar.disabled`) files are
/// considered; `.old` rollback archives are left to the native mod page.
///
/// Deletion is recoverable when possible: the file is routed to the OS recycle
/// bin / trash via {@link FileTrash} when the user's "delete to recycle bin"
/// preference (supplied to the constructor and read live on every call) is enabled
/// and the platform supports it; otherwise the native [`LocalModFile#delete()`] removes it
/// permanently.
///
/// A failed deletion is attributed via [GameResourceGuard#checkInstanceNotRunning(String)]:
/// when the instance is being played the failure says so ("file held open by the running game,
/// quit and retry; nothing was changed") instead of leaking a raw I/O message.
///
/// Permission level: {@link ToolPermission#CONTROLLED_WRITE}. It removes exactly
/// one mod file and never touches anything else.
@NotNullByDefault
public final class DeleteModTool implements ToolSpec {

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
                   "mod": {"type": "string", "description": "The mod file name, or a case-insensitive substring of the file name, display name or mod id that matches exactly one installed mod (matches both enabled .jar and disabled .jar.disabled files)."},
                   "instance": {"type": "string", "description": "Optional instance/version id; defaults to the currently selected instance."}
                 },
                 "required": ["mod"]
               }
               """;
    }

    @Override
    public String getDescription() {
        return "Removes a single mod from an instance's mods folder (use this instead of a raw file delete). "
                + "Parameters: mod (required, the mod file name — or a case-insensitive substring of the file name, "
                + "display name or mod id — that matches exactly one installed mod; "
                + "matches both enabled '.jar' and disabled '.jar.disabled' files), "
                + "instance (optional, the instance/version id; defaults to the currently selected instance). "
                + "The file is moved to the system recycle bin when possible (recoverable), otherwise permanently deleted. "
                + "Fails if the substring matches zero or more than one mod, so it never deletes the wrong mod. "
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

        if (!repository.isLoaded()) {
            try {
                repository.refreshVersions();
            } catch (Throwable e) {
                return ToolResult.failure("Failed to load installed instances: " + e.getMessage());
            }
        }

        InstanceToolSupport.ResolvedInstance resolvedTarget =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (resolvedTarget.failure() != null) {
            return resolvedTarget.failure();
        }
        String instanceId = resolvedTarget.name();

        Path modsDir;
        try {
            modsDir = repository.getRunDirectory(instanceId).resolve("mods");
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve mods directory: " + e.getMessage());
        }
        if (!Files.isDirectory(modsDir)) {
            return ToolFailures.failure(
                    "Mods directory does not exist for instance '" + instanceId + "': " + modsDir,
                    ToolFailures.Retryable.NO,
                    "the instance has no mods folder yet, so there is nothing to delete",
                    "Use list_mods to see the installed mods, or check the instance id");
        }

        // Resolve the mod through the native ModManager/LocalModFile state machine, exactly
        // like ToggleModTool (shared helper).
        ToggleModTool.ResolvedMod resolved =
                ToggleModTool.resolveTrackedMod(repository.getModManager(instanceId), modsDir, modQuery);
        if (resolved.failure() != null) {
            return resolved.failure();
        }
        LocalModFile mod = resolved.mod();

        Path target = mod.getFile();
        String fileName = target.getFileName().toString();
        boolean wasDisabled = mod.isDisabled();

        boolean recycled;
        try {
            if (toRecycleBin.getAsBoolean()) {
                recycled = FileTrash.delete(target, true);
            } else {
                // The native state-machine delete (the same call behind ModManager#removeMods).
                mod.delete();
                recycled = false;
            }
        } catch (Throwable e) {
            return ToggleModTool.fileOperationFailure(instanceId,
                    "Deleting mod '" + fileName + "' from instance '" + instanceId + "' failed", e);
        }

        return ToolResult.success((recycled
                ? "Moved mod '" + fileName + "' to the system recycle bin (recoverable).\n"
                : "Permanently deleted mod '" + fileName + "' from disk.\n")
                + "  instance: " + instanceId + "\n"
                + "  was     : " + (wasDisabled ? "disabled" : "enabled") + "\n"
                + "  path    : " + target);
    }
}
