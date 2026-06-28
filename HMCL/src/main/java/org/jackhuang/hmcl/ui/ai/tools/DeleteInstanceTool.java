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

/// DANGEROUS / IRREVERSIBLE: permanently deletes a Minecraft instance (version) from disk.
///
/// Reuses the exact repository call performed by the native delete action in
/// {@code Versions.deleteVersion}: {@link HMCLGameRepository#removeVersionFromDisk(String)}.
///
/// As a safety net (until a real UI confirmation dialog is wired in), this tool requires
/// an explicit {@code confirm=true} parameter. Without it, nothing is deleted and the tool
/// returns a failure explaining what would be deleted and how to confirm.
@NotNullByDefault
public final class DeleteInstanceTool implements Tool {

    @Override
    public String getName() {
        return "delete_instance";
    }

    @Override
    public String getDescription() {
        return "DANGEROUS / IRREVERSIBLE: permanently DELETE a Minecraft instance (version) from disk. "
                + "This cannot be undone. "
                + "Parameters: instance (instance name to delete; falls back to 'query'), "
                + "confirm (REQUIRED boolean). "
                + "If confirm is not exactly true, NOTHING is deleted and the tool reports what would be removed; "
                + "you must then re-invoke with confirm=true to actually delete. "
                + "Only use this when the user has clearly asked to delete that specific instance.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String instance = InstanceToolSupport.instanceName(parameters);

        if (instance == null) {
            return ToolResult.failure("Missing required parameter: 'instance' (or 'query').");
        }

        HMCLGameRepository repository = InstanceToolSupport.repository();

        if (!repository.isLoaded()) {
            return ToolResult.failure("The game repository is not loaded yet; please try again in a moment.");
        }
        if (!repository.hasVersion(instance)) {
            return ToolResult.failure("No such instance: '" + instance + "'.");
        }

        // Confirm-gate: do NOT delete unless confirm is exactly true.
        if (!InstanceToolSupport.bool(parameters, "confirm")) {
            return ToolResult.failure("Not confirmed: this will PERMANENTLY DELETE the instance '" + instance
                    + "' and all of its files from disk. This action is IRREVERSIBLE. "
                    + "Re-invoke delete_instance with confirm=true to proceed.");
        }

        boolean ok = repository.removeVersionFromDisk(instance);
        if (!ok) {
            return ToolResult.failure("Failed to delete instance '" + instance
                    + "' from disk (an I/O error occurred).");
        }

        repository.refreshVersions();
        return ToolResult.success("Permanently deleted instance '" + instance + "' from disk.");
    }
}
