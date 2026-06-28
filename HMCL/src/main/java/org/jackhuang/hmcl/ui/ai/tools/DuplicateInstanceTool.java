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
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Map;

/// Duplicates a Minecraft instance (version) of the currently selected profile to a new name.
///
/// Reuses the exact repository call performed by the native duplicate action in
/// {@code Versions.duplicateVersion}: {@link HMCLGameRepository#duplicateVersion(String, String, boolean)}.
/// On failure, any partially-created instance is removed via
/// {@link HMCLGameRepository#removeVersionFromDisk(String)}, mirroring the native cleanup.
@NotNullByDefault
public final class DuplicateInstanceTool implements Tool {

    @Override
    public String getName() {
        return "duplicate_instance";
    }

    @Override
    public String getDescription() {
        return "Copy an existing Minecraft instance (version) to a new instance in the currently selected profile. "
                + "Parameters: instance (source instance name; falls back to 'query'), "
                + "newName (the name of the new copy), "
                + "copySaves (optional boolean, default false; whether to also copy the 'saves' folder). "
                + "The new name must be a valid version id and must not already be used. "
                + "Returns the source and new name on success.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String instance = InstanceToolSupport.instanceName(parameters);
        String newName = InstanceToolSupport.string(parameters, "newName");
        boolean copySaves = InstanceToolSupport.bool(parameters, "copySaves");

        if (instance == null) {
            return ToolResult.failure("Missing required parameter: 'instance' (or 'query').");
        }
        if (newName == null) {
            return ToolResult.failure("Missing required parameter: 'newName'.");
        }

        HMCLGameRepository repository = InstanceToolSupport.repository();

        if (!repository.isLoaded()) {
            return ToolResult.failure("The game repository is not loaded yet; please try again in a moment.");
        }
        if (!repository.hasVersion(instance)) {
            return ToolResult.failure("No such instance: '" + instance + "'.");
        }
        if (!HMCLGameRepository.isValidVersionId(newName)) {
            return ToolResult.failure("Invalid new instance name: '" + newName + "'.");
        }
        if (repository.versionIdConflicts(newName)) {
            return ToolResult.failure("The name '" + newName + "' is already taken by another instance.");
        }

        try {
            repository.duplicateVersion(instance, newName, copySaves);
        } catch (IOException e) {
            // Mirror the native cleanup: remove any partially-created instance.
            if (!repository.versionIdConflicts(newName)) {
                repository.removeVersionFromDisk(newName);
            }
            return ToolResult.failure("Failed to duplicate '" + instance + "' to '" + newName
                    + "': " + e.getMessage());
        }

        repository.refreshVersions();
        return ToolResult.success("Duplicated instance '" + instance + "' to '" + newName + "'"
                + (copySaves ? " (including saves)." : "."));
    }
}
