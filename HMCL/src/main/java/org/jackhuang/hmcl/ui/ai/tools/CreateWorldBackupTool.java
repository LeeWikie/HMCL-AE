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

import java.util.Map;
import java.util.function.IntSupplier;

/// Creates a versioned, timestamped snapshot of a single world and keeps only
/// the newest N snapshots (retention from AI settings).
///
/// Backed by {@link WorldBackupManager}. NOTE (honest): each snapshot is a FULL
/// copy of `saves/<world>` (not incremental/git) — see WorldBackupManager.
///
/// Permission level: it only reads the source world and writes a new backup
/// folder; the live save is never modified. (Pruning may delete old *backups*.)
@NotNullByDefault
public final class CreateWorldBackupTool implements Tool {

    private final IntSupplier retentionSupplier;

    /// @param retentionSupplier supplies the current retention count (from AI settings)
    public CreateWorldBackupTool(IntSupplier retentionSupplier) {
        this.retentionSupplier = retentionSupplier;
    }

    @Override
    public String getName() {
        return "create_world_backup";
    }

    @Override
    public String getDescription() {
        return "Creates a VERSIONED, timestamped backup of a single Minecraft world and keeps only the newest N "
                + "snapshots (retention is configured in AI settings). Each backup is a FULL copy of saves/<world> "
                + "into '<runDir>/ai-world-backups/<world>/<yyyyMMdd-HHmmss>/' — this is NOT incremental/git-style, "
                + "so snapshots do not share storage (a future version may switch to incremental). "
                + "Parameters: world (required, the save folder name under 'saves/'), "
                + "instance (optional, defaults to the currently selected instance). "
                + "Use list_world_backups to see snapshots and restore_world_backup to roll back. "
                + "Only reads the live save and writes a new backup; the original world is never modified.";
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

        int retention = retentionSupplier.getAsInt();

        try {
            WorldBackupManager.BackupResult result =
                    WorldBackupManager.createBackup(instance, world, retention);
            StringBuilder sb = new StringBuilder();
            sb.append("Created backup of world '").append(world).append("'.\n");
            sb.append("Snapshot id: ").append(result.id()).append('\n');
            sb.append("Path: ").append(result.backupPath()).append('\n');
            sb.append("Files: ").append(result.fileCount())
                    .append(" (").append(WorldBackupManager.humanBytes(result.sizeBytes())).append(")\n");
            sb.append("Retention: keep newest ").append(retention <= 0 ? "all (unlimited)" : retention);
            if (result.prunedCount() > 0) {
                sb.append(" — pruned ").append(result.prunedCount()).append(" old snapshot(s)");
            }
            sb.append('\n');
            sb.append("Note: this is a full copy (not incremental).");
            return ToolResult.success(sb.toString());
        } catch (Throwable e) {
            return ToolResult.failure("Failed to back up world '" + world + "': " + e.getMessage());
        }
    }
}
