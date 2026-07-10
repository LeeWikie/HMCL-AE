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
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Map;
import java.util.function.IntSupplier;

/// HIGH RISK / DESTRUCTIVE: restores a world from one of its versioned snapshots,
/// OVERWRITING the current `saves/<world>`.
///
/// As a guard, the CURRENT world is automatically snapshotted first (so a wrong
/// restore can itself be undone) before being replaced. Backed by
/// {@link WorldBackupManager}.
///
/// This operation is registered in {@code CriticalOperations.CRITICAL_ACTIONS} (keyed on the
/// merged `instance` tool's `worlds_backup_restore` action), so executing it raises the red
/// second-tier confirmation.
@NotNullByDefault
public final class RestoreWorldBackupTool implements Tool {

    private final IntSupplier maxTotalMegabytesSupplier;

    /// @param maxTotalMegabytesSupplier supplies the per-world cap on the total snapshot size,
    ///                                  in MB (from AI settings), applied to the post-restore
    ///                                  prune of the world's snapshots
    public RestoreWorldBackupTool(IntSupplier maxTotalMegabytesSupplier) {
        this.maxTotalMegabytesSupplier = maxTotalMegabytesSupplier;
    }

    @Override
    public String getName() {
        return "restore_world_backup";
    }

    @Override
    public String getDescription() {
        return "HIGH RISK / DESTRUCTIVE: restores a world from a previously created snapshot, OVERWRITING the "
                + "current saves/<world>. Before overwriting, the CURRENT world is automatically backed up first "
                + "(so a wrong restore can be undone). "
                + "Parameters: world (required, the save folder name), backupId (required, the snapshot timestamp id "
                + "from instance(action=\"worlds_backup_list\"), e.g. 20260629-153000), instance (optional, defaults "
                + "to the selected instance). "
                + "Always run instance(action=\"worlds_backup_list\") first to pick the correct backupId, and only "
                + "restore when the user clearly asked to roll this world back.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        final String world = ToolParams.primary(parameters, "world",
                new String[]{"backupId", "instance"}, "save", "folder", "saveName", "name");
        if (world.isEmpty()) {
            return ToolResult.failure("Parameter 'world' (the save folder name) is required.");
        }

        Object backupObj = parameters.get("backupId");
        if (!(backupObj instanceof String) || ((String) backupObj).trim().isEmpty()) {
            return ToolResult.failure("Parameter 'backupId' (a snapshot timestamp from "
                    + "instance(action=\"worlds_backup_list\")) is required.");
        }
        String backupId = ((String) backupObj).trim();

        HMCLGameRepository repository;
        try {
            repository = Profiles.getSelectedProfile().getRepository();
        } catch (Throwable e) {
            return ToolResult.failure("No profile is currently selected: " + e.getMessage());
        }
        InstanceToolSupport.ResolvedInstance target =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (target.failure() != null) {
            return target.failure();
        }
        String instance = target.name();

        int maxTotalMegabytes = maxTotalMegabytesSupplier.getAsInt();

        try {
            WorldBackupManager.RestoreResult result =
                    WorldBackupManager.restore(instance, world, backupId, maxTotalMegabytes);
            StringBuilder sb = new StringBuilder();
            sb.append("Restored world '").append(world).append("' from snapshot ").append(backupId).append(".\n");
            sb.append("Restored path: ").append(result.restoredWorldPath()).append('\n');
            sb.append("Files restored: ").append(result.fileCount()).append('\n');
            if (result.safetyBackupId() != null) {
                sb.append("The pre-restore world was saved as snapshot ").append(result.safetyBackupId())
                        .append(" — restore that id to undo this if needed.");
            } else {
                sb.append("The world did not exist before, so no safety backup was needed.");
            }
            return ToolResult.success(sb.toString());
        } catch (Throwable e) {
            return ToolResult.failure("Failed to restore world '" + world + "' from '" + backupId + "': " + e.getMessage());
        }
    }
}
