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

import java.util.Map;

/// Consolidated tool for non-destructive edits to a Minecraft instance of the selected
/// profile. Chosen via the {@code action} parameter; designed to grow as more native
/// instance operations are migrated in.
///
/// - {@code rename}: reuses {@link HMCLGameRepository#renameVersion(String, String)}.
/// - Game settings (memory, JVM args, Java, window) are edited through the {@code config-hmcl}
///   skill, which writes the instance config files directly (HMCL's GameSettings system uses
///   per-instance override flags and is rewritten on exit, so direct mutation is avoided here).
/// - Deletion is intentionally NOT here — it is the separate, dangerous, confirm-gated
///   {@code delete_instance} tool.
@NotNullByDefault
public final class EditInstanceTool implements Tool {

    @Override
    public String getName() {
        return "edit_instance";
    }

    @Override
    public String getDescription() {
        return "Edit a Minecraft instance in the selected profile. Parameter 'action' selects the "
                + "operation:\n"
                + "- 'rename' — rename the instance. Params: instance (current name; falls back to 'query'), "
                + "newName (the new name).\n"
                + "For game settings (memory / JVM args / Java path / window size) use the 'config-hmcl' skill "
                + "to edit the instance config files. To DELETE an instance use the separate 'delete_instance' "
                + "tool (dangerous, requires confirmation).";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String action = InstanceToolSupport.string(parameters, "action");
        if (action == null) {
            action = "rename"; // default + currently the only supported action
        }
        switch (action.toLowerCase()) {
            case "rename":
                return rename(parameters);
            default:
                return ToolResult.failure("Unknown action '" + action + "'. Supported: rename. "
                        + "For game settings use the config-hmcl skill; to delete an instance use delete_instance.");
        }
    }

    private ToolResult rename(Map<String, Object> parameters) {
        String instance = InstanceToolSupport.instanceName(parameters);
        String newName = InstanceToolSupport.string(parameters, "newName");

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
        if (newName.equals(instance)) {
            return ToolResult.success("Instance '" + instance + "' already has that name; nothing to do.");
        }
        if (!HMCLGameRepository.isValidVersionId(newName)) {
            return ToolResult.failure("Invalid new instance name: '" + newName + "'.");
        }
        if (repository.versionIdConflicts(newName)) {
            return ToolResult.failure("The name '" + newName + "' is already taken by another instance.");
        }

        boolean ok;
        try {
            ok = repository.renameVersion(instance, newName);
        } catch (UnsupportedOperationException e) {
            return ToolResult.failure("This repository does not support renaming instances.");
        }
        if (!ok) {
            return ToolResult.failure("Failed to rename '" + instance + "' to '" + newName
                    + "' (the version json may be malformed or an I/O error occurred).");
        }

        repository.refreshVersions();
        return ToolResult.success("Renamed instance '" + instance + "' to '" + newName + "'.");
    }
}
