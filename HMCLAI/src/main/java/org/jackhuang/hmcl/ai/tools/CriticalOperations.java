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
import java.util.Map;
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

    /// Tool name → dangerous action set. An EMPTY set means the whole tool is catastrophic
    /// regardless of arguments (legacy standalone tools that have no merged domain today); a
    /// NON-EMPTY set scopes the check to specific `action` values on a merged domain tool (e.g.
    /// `instance` — action=list is harmless, action=delete isn't).
    private static final Map<String, Set<String>> CRITICAL_ACTIONS = Map.ofEntries(
            // delete_world / delete_instance / restore_world_backup used to be separate
            // whole-tool entries; they're now actions on the merged `instance` domain tool.
            // mods_delete/mods_update both go through FileTrash.delete() on the mod jar (same as
            // InstanceTool.getPermission()'s DANGEROUS_WRITE classification for them) — they get
            // the same second (red) confirmation as the other destructive instance actions.
            // resourcepacks_delete/shaders_delete follow the exact same FileTrash.delete()-based
            // destructive-delete pattern (see DeleteResourcePackTool/DeleteShaderTool), so they
            // get the same second (red) confirmation too.
            Map.entry("instance", Set.of("delete", "worlds_delete", "worlds_backup_restore", "mods_delete", "mods_update",
                    "resourcepacks_delete", "shaders_delete")),
            // set_nbt / copy_player_data / transfer_inventory likewise, on the merged `nbt` tool.
            // delete_nbt / add_nbt / set_world_info have no corresponding tool today — kept as
            // inert entries (they simply never match) in case those land later.
            Map.entry("nbt", Set.of("set", "copy_player_data", "transfer_inventory")),
            Map.entry("delete_nbt", Set.of()),
            Map.entry("add_nbt", Set.of()),
            Map.entry("set_world_info", Set.of()));

    /// A shell delete verb (rm / del / rmdir / Remove-Item and its `ri` alias / unlink / rd).
    private static final Pattern DELETE_VERB = Pattern.compile(
            "(?i)(\\brm\\b|\\brmdir\\b|\\bunlink\\b|\\bdel\\b|\\berase\\b|\\brd\\b|\\bri\\b|remove-item|rimraf)");

    /// Paths whose deletion is catastrophic: saves, player data, backups, level data, or the
    /// whole .minecraft. Matched only WHEN a delete verb is also present.
    private static final Pattern CRITICAL_PATH = Pattern.compile(
            "(?i)(saves|playerdata|players[/\\\\]data|level\\.dat|\\.minecraft|"
                    + "saves-backups|backups?\\b|\\.bak\\b|world)");

    /// Returns a human-readable Chinese reason when the invocation is critical, or {@code null}
    /// when it is not (so the caller knows whether to raise the red confirmation).
    ///
    /// Convenience overload for callers (and existing tests) that only have the raw arguments
    /// JSON — the `action` is extracted from it via {@link #extractAction}, which only looks at a
    /// TOP-LEVEL `action` key. See {@link #criticalReason(String, String, String)} for callers
    /// that already have the tool adapter's own unwrapped parameters (e.g. a `query`-nested
    /// `{"action":...}` payload), which that overload resolves correctly and this one cannot.
    ///
    /// @param toolName the tool being invoked
    /// @param argumentsJson the raw arguments (may contain a shell command / file paths)
    @Nullable
    public static String criticalReason(String toolName, @Nullable String argumentsJson) {
        return criticalReason(toolName, null, argumentsJson);
    }

    /// Same as {@link #criticalReason(String, String)}, but takes the already-resolved `action`
    /// (from the caller's own unwrapped parameter map) instead of re-deriving it from the raw
    /// arguments JSON. This matters because {@link org.jackhuang.hmcl.ai.langchain4j.LangChain4jToolAdapter}
    /// unwraps a nested `{"query":"{\"action\":...}"}` payload before resolving permissions/executing
    /// (see its `parseArguments`), but {@link #extractAction} only ever looks at a TOP-LEVEL `action`
    /// key in the RAW json — so a call shaped that way would resolve DANGEROUS_WRITE for the normal
    /// permission gate (which uses the unwrapped map) while this class's own gate silently missed it.
    /// Passing {@code null} for `resolvedAction` falls back to {@link #extractAction} exactly as
    /// before, so existing single-arg callers are unaffected.
    ///
    /// @param toolName      the tool being invoked
    /// @param resolvedAction the caller's already-unwrapped `action` parameter value, or
    ///                       {@code null} to derive it from {@code argumentsJson} instead
    /// @param argumentsJson the raw arguments (may contain a shell command / file paths) — always
    ///                      also scanned as a fallback for the delete-verb/critical-path check
    @Nullable
    public static String criticalReason(String toolName, @Nullable String resolvedAction, @Nullable String argumentsJson) {
        if (toolName == null) {
            return null;
        }
        String name = toolName.toLowerCase(Locale.ROOT);

        String action = resolvedAction != null && !resolvedAction.isBlank()
                ? resolvedAction.trim().toLowerCase(Locale.ROOT)
                : extractAction(argumentsJson);
        Set<String> actions = CRITICAL_ACTIONS.get(name);
        boolean criticalHit = actions != null && (actions.isEmpty() || actions.contains(action));
        if (criticalHit) {
            if ("instance".equals(name)) {
                return switch (action == null ? "" : action) {
                    case "delete" -> "将永久删除一个游戏实例及其文件（不可恢复）。";
                    case "worlds_delete" -> "将永久删除一个世界存档（不可恢复）。";
                    case "worlds_backup_restore" -> "将用历史备份覆盖当前世界存档（当前存档会被替换，已自动先备份一次）。";
                    case "mods_delete" -> "将删除一个模组文件（是否可恢复取决于回收站设置）。";
                    case "mods_update" -> "将用新版本覆盖并删除旧的模组文件（是否可恢复取决于回收站设置）。";
                    case "resourcepacks_delete" -> "将删除一个资源包文件（是否可恢复取决于回收站设置）。";
                    case "shaders_delete" -> "将删除一个光影包文件（是否可恢复取决于回收站设置）。";
                    default -> "这是一项可能不可逆的高危操作。";
                };
            }
            if ("nbt".equals(name)) {
                return switch (action == null ? "" : action) {
                    case "set" -> "将直接修改存档的 NBT 数据，改错可能导致存档损坏。";
                    case "copy_player_data" -> "将覆盖目标玩家数据文件（playerdata），原数据会被替换。";
                    case "transfer_inventory" -> "将覆盖目标玩家的物品栏数据。";
                    default -> "这是一项可能不可逆的高危操作。";
                };
            }
            return switch (name) {
                case "delete_nbt", "add_nbt" -> "将直接修改存档的 NBT 数据，改错可能导致存档损坏。";
                case "set_world_info" -> "将修改世界核心数据（难度/玩家数据等）。";
                default -> "这是一项可能不可逆的高危操作。";
            };
        }

        // Shell (or any tool) whose arguments delete files under a critical path.
        if (argumentsJson != null && !argumentsJson.isEmpty()) {
            if (deletesCriticalPath(argumentsJson)) {
                return "检测到对 存档 / 玩家数据 / 备份 / .minecraft 关键目录 的删除操作（不可恢复）。";
            }
            // Encoding-bypass hardening: a destructive command can be hidden in a base64 payload
            // (PowerShell -EncodedCommand / -enc / -e, or a bare base64 blob). Decode it and re-run
            // the same critical-path check on the decoded text; an opaque/undecodable encoding
            // wrapper is treated as critical (fail-closed). Shared with DangerousCommands.
            DangerousCommands.EncodedScan enc =
                    DangerousCommands.scanEncodedPayloads(argumentsJson, CriticalOperations::deletesCriticalPath);
            if (enc == DangerousCommands.EncodedScan.MATCH) {
                return "检测到（base64 解码后）对 存档 / 玩家数据 / 备份 / .minecraft 关键目录 的删除操作（不可恢复）。";
            }
            if (enc == DangerousCommands.EncodedScan.UNDECODABLE) {
                return "检测到无法安全解码的编码命令（如 PowerShell -EncodedCommand），可能隐藏对 存档 / 玩家数据 的破坏性操作，已按高危处理。";
            }
        }
        return null;
    }

    /// Best-effort extraction of the {@code action} parameter from a tool call's raw arguments
    /// JSON, for the action-scoped entries in {@link #CRITICAL_ACTIONS}. Returns {@code null} on
    /// anything that isn't a JSON object with a string `action` field (including plain-string
    /// arguments from tools with no structured schema) — callers treat that as "no action",
    /// which only matches an EMPTY action set (whole-tool-critical), never a non-empty one.
    @Nullable
    private static String extractAction(@Nullable String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(argumentsJson);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("action")) {
                return null;
            }
            com.google.gson.JsonElement action = root.getAsJsonObject().get("action");
            return action.isJsonPrimitive() ? action.getAsString().trim().toLowerCase(Locale.ROOT) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /// True when {@code text} contains BOTH a delete verb (or a PowerShell indirect-invocation
    /// construct we cannot rule out as assembling one — fail-closed, mirroring
    /// {@link DangerousCommands}'s handling of an undecodable encoded payload) AND a catastrophic
    /// path — the condition for a critical shell deletion. Checked first against the raw text and
    /// then, only if that found nothing, against the obfuscation-normalized text (mid-word verb
    /// splitting / `$IFS` whitespace substitution — see {@link DangerousCommands#normalizeObfuscation}),
    /// so every existing raw-text match keeps working exactly as before while normalization can only
    /// add detections. Used directly on the raw arguments and, via
    /// {@link DangerousCommands#scanEncodedPayloads}, on any decoded base64 payload.
    private static boolean deletesCriticalPath(String text) {
        if (deletesCriticalPathRaw(text)) {
            return true;
        }
        String normalized = DangerousCommands.normalizeObfuscation(text);
        return !normalized.equals(text) && deletesCriticalPathRaw(normalized);
    }

    private static boolean deletesCriticalPathRaw(String text) {
        boolean verbLikely = DELETE_VERB.matcher(text).find() || DangerousCommands.hasIndirectInvocation(text);
        return verbLikely && CRITICAL_PATH.matcher(text).find();
    }

    /// Convenience boolean form.
    public static boolean isCritical(String toolName, @Nullable String argumentsJson) {
        return criticalReason(toolName, argumentsJson) != null;
    }
}
