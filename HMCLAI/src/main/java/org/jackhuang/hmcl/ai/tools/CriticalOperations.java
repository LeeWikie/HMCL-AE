/*
 * Hello Minecraft! Launcher
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
package org.jackhuang.hmcl.ai.tools;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/// Classifies a tool invocation as a CRITICAL / catastrophic operation that warrants a
/// second, distinct (red) confirmation ON TOP of the normal danger confirmation.
///
/// This is intentionally NARROW — it is NOT "all writes" or "all rm". It targets the few
/// operations that can irreversibly destroy a user's worlds, player data, or their backups:
///   - deleting/overwriting save data, player data (playerdata/&lt;uuid&gt;.dat), level.dat
///   - editing NBT inside saves (set/delete/add nbt)
///   - deleting an instance
///   - shell commands that delete files **under a saves / backup / .minecraft critical path**
///     (a plain `rm tmp.txt` is NOT critical; `rm -rf .../saves/...` IS)
///
/// The normal {@link DangerousCommands} / {@link AiExecutionPolicy} layer still runs first;
/// this is the extra gate evaluated right before execution.
@NotNullByDefault
public final class CriticalOperations {

    private CriticalOperations() {
    }

    /// Tool names whose very invocation is catastrophic (irreversible save/player/instance data
    /// destruction or overwrite). These always trigger the red confirmation.
    private static final Set<String> CRITICAL_TOOLS = Set.of(
            "delete_world",
            "delete_instance",
            "set_nbt",
            "delete_nbt",
            "add_nbt",
            "set_world_info",
            "copy_player_data",
            "transfer_inventory",
            "restore_world_backup");

    /// A shell delete verb (rm / del / rmdir / Remove-Item / unlink / rd).
    private static final Pattern DELETE_VERB = Pattern.compile(
            "(?i)(\\brm\\b|\\brmdir\\b|\\bunlink\\b|\\bdel\\b|\\berase\\b|\\brd\\b|remove-item|rimraf)");

    /// Paths whose deletion is catastrophic: saves, player data, backups, level data, or the
    /// whole .minecraft. Matched only WHEN a delete verb is also present.
    private static final Pattern CRITICAL_PATH = Pattern.compile(
            "(?i)(saves|playerdata|players[/\\\\]data|level\\.dat|\\.minecraft|"
                    + "saves-backups|backups?\\b|\\.bak\\b|world)");

    /// Returns a human-readable Chinese reason when the invocation is critical, or {@code null}
    /// when it is not (so the caller knows whether to raise the red confirmation).
    ///
    /// @param toolName the tool being invoked
    /// @param argumentsJson the raw arguments (may contain a shell command / file paths)
    @Nullable
    public static String criticalReason(String toolName, @Nullable String argumentsJson) {
        if (toolName == null) {
            return null;
        }
        String name = toolName.toLowerCase(Locale.ROOT);

        if (CRITICAL_TOOLS.contains(name)) {
            return switch (name) {
                case "delete_world" -> "将永久删除一个世界存档（不可恢复）。";
                case "delete_instance" -> "将永久删除一个游戏实例及其文件（不可恢复）。";
                case "set_nbt", "delete_nbt", "add_nbt" -> "将直接修改存档的 NBT 数据，改错可能导致存档损坏。";
                case "set_world_info" -> "将修改世界核心数据（难度/玩家数据等）。";
                case "copy_player_data" -> "将覆盖目标玩家数据文件（playerdata），原数据会被替换。";
                case "transfer_inventory" -> "将覆盖目标玩家的物品栏数据。";
                case "restore_world_backup" -> "将用历史备份覆盖当前世界存档（当前存档会被替换，已自动先备份一次）。";
                default -> "这是一项可能不可逆的高危操作。";
            };
        }

        // Shell (or any tool) whose arguments delete files under a critical path.
        if (argumentsJson != null && !argumentsJson.isEmpty()) {
            boolean deletes = DELETE_VERB.matcher(argumentsJson).find();
            if (deletes && CRITICAL_PATH.matcher(argumentsJson).find()) {
                return "检测到对 存档 / 玩家数据 / 备份 / .minecraft 关键目录 的删除操作（不可恢复）。";
            }
        }
        return null;
    }

    /// Convenience boolean form.
    public static boolean isCritical(String toolName, @Nullable String argumentsJson) {
        return criticalReason(toolName, argumentsJson) != null;
    }
}
