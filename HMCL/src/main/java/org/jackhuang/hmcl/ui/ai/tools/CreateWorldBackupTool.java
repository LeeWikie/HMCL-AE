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

/// Creates a versioned, timestamped snapshot of a single world and prunes the
/// oldest snapshots once the per-world total size exceeds the cap (MB, from AI
/// settings); the newest snapshot is always kept.
///
/// Backed by {@link WorldBackupManager}. NOTE (honest): each snapshot is a FULL
/// copy of `saves/<world>` (not incremental/git) — see WorldBackupManager.
///
/// Permission level: it only reads the source world and writes a new backup
/// folder; the live save is never modified. (Pruning may delete old *backups*.)
@NotNullByDefault
public final class CreateWorldBackupTool implements Tool {

    private final IntSupplier maxTotalMegabytesSupplier;

    /// @param maxTotalMegabytesSupplier supplies the per-world cap on the total snapshot size,
    ///                                  in MB (from AI settings); {@code <= 0} disables pruning
    public CreateWorldBackupTool(IntSupplier maxTotalMegabytesSupplier) {
        this.maxTotalMegabytesSupplier = maxTotalMegabytesSupplier;
    }

    @Override
    public String getName() {
        return "create_world_backup";
    }

    @Override
    public String getDescription() {
        return "Creates a VERSIONED, timestamped backup of a single Minecraft world; once the world's snapshots "
                + "exceed a total-size cap (MB, configured in AI settings) the oldest are pruned automatically, "
                + "and the newest snapshot is always kept. Each backup is a FULL copy of saves/<world> "
                + "into '<runDir>/ai-world-backups/<world>/<yyyyMMdd-HHmmss>/' — this is NOT incremental/git-style, "
                + "so snapshots do not share storage (a future version may switch to incremental). "
                + "Parameters: world (required, the save folder name under 'saves/'), "
                + "instance (optional, defaults to the currently selected instance). "
                + "Use list_world_backups to see snapshots and restore_world_backup to roll back. "
                + "Only reads the live save and writes a new backup; the original world is never modified.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String world = ToolParams.primary(parameters, "world",
                new String[]{"instance"}, "save", "folder", "saveName", "name");
        if (world.isEmpty()) {
            return ToolResult.failure("Parameter 'world' (the save folder name) is required.");
        }

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
            WorldBackupManager.BackupResult result =
                    WorldBackupManager.createBackup(instance, world, maxTotalMegabytes);
            StringBuilder sb = new StringBuilder();
            sb.append("Created backup of world '").append(world).append("'.\n");
            sb.append("Snapshot id: ").append(result.id()).append('\n');
            sb.append("Path: ").append(result.backupPath()).append('\n');
            sb.append("Files: ").append(result.fileCount())
                    .append(" (").append(WorldBackupManager.humanBytes(result.sizeBytes())).append(")\n");
            sb.append("Size cap: ").append(maxTotalMegabytes <= 0
                    ? "unlimited (no pruning)" : maxTotalMegabytes + " MB total per world, newest always kept");
            if (result.prunedCount() > 0) {
                sb.append(" — pruned ").append(result.prunedCount()).append(" old snapshot(s)");
            }
            sb.append('\n');
            sb.append("Note: this is a full copy (not incremental).");
            if (result.lockedDuringBackup()) {
                sb.append("\nNOTE: the world was open in a running game while this backup was taken, so the "
                        + "snapshot may not represent a single consistent point in time. Suggest to the user to "
                        + "quit the world (or close the game) and back up again for a guaranteed-consistent snapshot.");
            }
            return ToolResult.success(sb.toString());
        } catch (Throwable e) {
            return ToolResult.failure("Failed to back up world '" + world + "': " + e.getMessage());
        }
    }
}
