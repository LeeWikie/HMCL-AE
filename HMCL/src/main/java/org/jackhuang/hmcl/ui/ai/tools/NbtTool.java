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

import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;
import java.util.Map;

/// Domain tool for save/player NBT editing. Hidden by default (like global memory) — surfaced
/// only when NBT tools are enabled in AI 设置; see {@code AiSettings#isNbtToolsEnabled()}. Writes
/// are backup-gated + path-confined + atomic, and `set`/`copy_player_data`/`transfer_inventory`
/// trigger the extra red critical confirmation via {@code CriticalOperations}.
@NotNullByDefault
public final class NbtTool implements ToolSpec {

    private final ReadNbtTool read = new ReadNbtTool();
    private final GetNbtTool get = new GetNbtTool();
    private final SetNbtTool set = new SetNbtTool();
    private final ComputeOfflineUuidTool offlineUuid = new ComputeOfflineUuidTool();
    private final CopyPlayerDataTool copyPlayerData = new CopyPlayerDataTool();
    private final TransferInventoryTool transferInventory = new TransferInventoryTool();

    @Override
    public String getName() {
        return "nbt";
    }

    @Override
    public String getDescription() {
        return "Save / player NBT editing. Parameter 'action' (required): "
                + "read(instance?, path | world+file, maxDepth?) — dump a save file as an indented tree, READ-ONLY; "
                + "get(instance?, path | world+file, nbtPath) — read one value/subtree at a dotted NBT path "
                + "('Data.Player.XpLevel', '[n]' for list indices), READ-ONLY; "
                + "set(instance?, path | world+file, nbtPath, value) — edit an EXISTING value at a dotted path "
                + "(auto-backs-up first, atomic write, refuses if the world is open in-game), WRITE/HIGH-RISK; "
                + "offline_uuid(username) — compute a username's offline-mode UUID, READ-ONLY, no files touched; "
                + "copy_player_data(instance?, world, from, to) — copy ALL of one player's save data onto another "
                + "player slot (rescues 'changed account, lost my save'), WRITE/HIGH-RISK; "
                + "transfer_inventory(instance?, world, from, to, includeEnderChest?) — move ONLY the inventory "
                + "(+ optionally ender chest), keeping the destination's position/health/XP, WRITE/HIGH-RISK. "
                + "from/to accept a UUID or a case-sensitive username (auto-converted to offline UUID). "
                + "Always tell the user to close the game first and recommend a world backup before set/copy/transfer.";
    }

    @Override
    public boolean supportsStructuredSchema() {
        return true;
    }

    @Override
    public String getInputSchemaJson() {
        // Flat, loosely-typed union of every action's parameters (matches the schema convention
        // used by the sibling merged domain tools, e.g. InstanceTool) — no oneOf branching, since
        // LangChain4jToolAdapter's schema parser only understands a flat properties map.
        return """
               {
                 "$schema": "https://json-schema.org/draft/2020-12/schema",
                 "type": "object",
                 "properties": {
                   "action": {"type": "string", "enum": ["read", "get", "set", "offline_uuid", "copy_player_data", "transfer_inventory"], "description": "Which NBT operation to perform (see tool description for the full list)."},
                   "instance": {"type": "string", "description": "Target instance id; defaults to the currently selected instance."},
                   "path": {"type": "string", "description": "read/get/set: absolute or instance-relative path to the save file (level.dat / playerdata/<uuid>.dat / etc.). Alternative to world+file."},
                   "world": {"type": "string", "description": "read/get/set: the save folder name under 'saves/', used with 'file' instead of 'path'. copy_player_data/transfer_inventory: the save folder name."},
                   "file": {"type": "string", "description": "read/get/set: the NBT file name within 'world' (e.g. 'level.dat', or a playerdata/<uuid>.dat file), used together with 'world'."},
                   "maxDepth": {"type": "integer", "description": "read: how many levels deep to expand the dumped tree; omit for the default depth."},
                   "nbtPath": {"type": "string", "description": "get/set: a dotted NBT path to a value/subtree, e.g. 'Data.Player.XpLevel'; use '[n]' for list indices."},
                   "value": {"type": "string", "description": "set: the new value to write at 'nbtPath'."},
                   "username": {"type": "string", "description": "offline_uuid: the username to compute the offline-mode UUID for."},
                   "from": {"type": "string", "description": "copy_player_data/transfer_inventory: the source player, a UUID or case-sensitive username (auto-converted to offline UUID)."},
                   "to": {"type": "string", "description": "copy_player_data/transfer_inventory: the destination player, a UUID or case-sensitive username (auto-converted to offline UUID)."},
                   "includeEnderChest": {"type": "boolean", "description": "transfer_inventory: also move the ender chest contents; defaults to false."}
                 },
                 "required": ["action"]
               }
               """;
    }

    @Override
    public ToolPermission getPermission(Map<String, Object> parameters) {
        String action = actionOf(parameters);
        return switch (action) {
            case "set", "copy_player_data", "transfer_inventory" -> ToolPermission.DANGEROUS_WRITE;
            default -> ToolPermission.READ_ONLY;
        };
    }

    /// This tool's worst case is DANGEROUS_WRITE (set/copy_player_data/transfer_inventory) —
    /// reported to the settings/catalog UI instead of the no-arg {@link #getPermission()} default.
    @Override
    public ToolPermission getMaxPermission() {
        return ToolPermission.DANGEROUS_WRITE;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String action = actionOf(parameters);
        return switch (action) {
            case "read" -> read.execute(parameters);
            case "get" -> get.execute(parameters);
            case "set" -> set.execute(parameters);
            case "offline_uuid" -> offlineUuid.execute(parameters);
            case "copy_player_data" -> copyPlayerData.execute(parameters);
            case "transfer_inventory" -> transferInventory.execute(parameters);
            default -> ToolResult.failure("Unknown action '" + action + "'. Valid actions: read, get, set, "
                    + "offline_uuid, copy_player_data, transfer_inventory.");
        };
    }

    private static String actionOf(Map<String, Object> parameters) {
        Object action = parameters.get("action");
        return action != null ? action.toString().trim().toLowerCase(Locale.ROOT) : "";
    }
}
