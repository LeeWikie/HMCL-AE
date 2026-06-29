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
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.Map;

/// Read-only: lists all versioned snapshots of a world created by
/// {@code create_world_backup}, newest first, with timestamp id, file count and
/// size. Backed by {@link WorldBackupManager}.
@NotNullByDefault
public final class ListWorldBackupsTool implements Tool {

    @Override
    public String getName() {
        return "list_world_backups";
    }

    @Override
    public String getDescription() {
        return "Lists the versioned backups (snapshots) of a single world created by create_world_backup, newest "
                + "first: each snapshot's id (a yyyyMMdd-HHmmss timestamp), file count and total size. "
                + "Parameters: world (required, the save folder name under 'saves/'), "
                + "instance (optional, defaults to the currently selected instance). Read-only. "
                + "Use a snapshot id with restore_world_backup to roll the world back to that point.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object worldObj = parameters.get("world");
        if (!(worldObj instanceof String) || ((String) worldObj).trim().isEmpty()) {
            return ToolResult.failure("Parameter 'world' (the save folder name) is required.");
        }
        String world = ((String) worldObj).trim();

        Object instanceObj = parameters.get("instance");
        String instance = instanceObj instanceof String && !((String) instanceObj).trim().isEmpty()
                ? ((String) instanceObj).trim() : null;

        try {
            List<WorldBackupManager.BackupInfo> backups =
                    WorldBackupManager.listBackups(instance, world);
            if (backups.isEmpty()) {
                return ToolResult.success("World '" + world + "' has no AI backups yet "
                        + "(use create_world_backup to make one).");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Backups of world '").append(world).append("' (").append(backups.size()).append("), newest first:\n");
            int index = 1;
            for (WorldBackupManager.BackupInfo info : backups) {
                sb.append("  ").append(index++).append(". ").append(info.id())
                        .append("  —  ").append(info.fileCount()).append(" files, ")
                        .append(WorldBackupManager.humanBytes(info.sizeBytes())).append('\n');
            }
            sb.append("Restore with restore_world_backup(world, backupId=<id>).");
            return ToolResult.success(sb.toString());
        } catch (Throwable e) {
            return ToolResult.failure("Failed to list backups of world '" + world + "': " + e.getMessage());
        }
    }
}
