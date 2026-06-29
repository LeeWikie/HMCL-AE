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

import org.glavo.nbt.tag.Tag;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.setting.Profile;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/// Transfers only the inventory (and optionally the ender chest) from one player to another
/// within the same world, leaving everything else (position, health, XP, advancements) on the
/// destination untouched. HIGH-RISK (writes to disk).
///
/// Unlike copy_player_data this is surgical: it clones just the `Inventory` list (and
/// `EnderItems` when requested) onto the destination player. Both players must already have
/// data in the world. If the two players' `DataVersion`s differ the result is still written
/// but a cross-version warning is included (item formats may not be fully compatible).
///
/// Safety: the destination is backed up first, the write is atomic and preserves compression,
/// and the operation is refused while the world is open in-game.
///
/// Permission level: WRITE / HIGH-RISK.
@NotNullByDefault
public final class TransferInventoryTool implements Tool {

    @Override
    public String getName() {
        return "transfer_inventory";
    }

    @Override
    public String getDescription() {
        return "Moves ONLY the inventory (optionally also the ender chest) from one player to another in the same "
                + "world, keeping the destination's position/health/XP. WRITE / HIGH-RISK. Parameters: instance "
                + "(optional); world (required); from (required, UUID or username); to (required, UUID or username); "
                + "includeEnderChest (optional boolean, default false). Usernames become offline UUIDs "
                + "(case-sensitive). Both players must already exist (in playerdata/, players/data/, or level.dat). "
                + "Clones the Inventory (and EnderItems) onto the destination, backs the destination up first, writes "
                + "atomically and preserves compression, refuses if the world is open in-game, and warns if the two "
                + "players' DataVersions differ. Advise closing the game and backing up the world.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        try {
            Profile profile = NbtToolSupport.requireProfile();
            String instance = NbtToolSupport.requireInstance(profile, parameters);
            Path savesDir = NbtToolSupport.savesDir(profile, instance);

            String world = NbtToolSupport.str(parameters, "world");
            Path worldDir = NbtToolSupport.resolveWorldDir(savesDir, world);

            String from = NbtToolSupport.str(parameters, "from");
            String to = NbtToolSupport.str(parameters, "to");
            if (from == null || to == null) {
                return ToolResult.failure("Both 'from' and 'to' (UUID or username) are required.");
            }
            boolean includeEnderChest = asBool(parameters.get("includeEnderChest"));

            UUID fromUuid = NbtToolSupport.resolvePlayerUuid(from);
            UUID toUuid = NbtToolSupport.resolvePlayerUuid(to);
            if (fromUuid.equals(toUuid)) {
                return ToolResult.failure("'from' and 'to' resolve to the same UUID (" + fromUuid + ").");
            }

            if (NbtToolSupport.isWorldLocked(worldDir)) {
                return ToolResult.failure("World '" + world + "' is currently open in the game. Quit to the title "
                        + "screen (or close Minecraft) first, then retry.");
            }

            NbtToolSupport.PlayerHandle source = NbtToolSupport.locatePlayer(worldDir, fromUuid);
            if (source == null) {
                return ToolResult.failure("Could not find source player data for " + fromUuid + " in world '" + world + "'.");
            }
            NbtToolSupport.PlayerHandle dest = NbtToolSupport.locatePlayer(worldDir, toUuid);
            if (dest == null) {
                return ToolResult.failure("Could not find destination player data for " + toUuid + " in world '" + world
                        + "'. The destination player must have joined the world at least once.");
            }

            Tag inventory = source.player.get("Inventory");
            if (inventory == null) {
                return ToolResult.failure("Source player has no Inventory tag; nothing to transfer.");
            }

            StringBuilder warn = new StringBuilder();
            Integer fromDV = source.player.getIntOrNull("DataVersion");
            Integer toDV = dest.player.getIntOrNull("DataVersion");
            if (fromDV != null && toDV != null && !fromDV.equals(toDV)) {
                warn.append("\nWARNING: DataVersion differs (source=").append(fromDV).append(", destination=")
                        .append(toDV).append("). The players are on different Minecraft versions; item formats may "
                        + "not be fully compatible — verify in-game.");
            }

            // Replace destination Inventory with a detached clone of the source's.
            Tag oldInv = dest.player.get("Inventory");
            if (oldInv != null) {
                dest.player.removeTag(oldInv);
            }
            dest.player.addTag("Inventory", inventory.clone());

            String enderNote = "";
            if (includeEnderChest) {
                Tag ender = source.player.get("EnderItems");
                if (ender != null) {
                    Tag oldEnder = dest.player.get("EnderItems");
                    if (oldEnder != null) {
                        dest.player.removeTag(oldEnder);
                    }
                    dest.player.addTag("EnderItems", ender.clone());
                    enderNote = "  (ender chest included)";
                } else {
                    enderNote = "  (source has no EnderItems; ender chest left unchanged)";
                }
            }

            Path backup = NbtToolSupport.backup(dest.backingFile);
            NbtToolSupport.writeTag(dest.backingFile, (Tag) dest.root(), dest.gzip);

            StringBuilder sb = new StringBuilder();
            sb.append("Transferred inventory in world '").append(world).append("':\n");
            sb.append("  from: ").append(from).append("  (").append(fromUuid).append(")\n");
            sb.append("  to:   ").append(to).append("  (").append(toUuid).append(")").append(enderNote).append('\n');
            sb.append("  destination file: ").append(savesDir.relativize(dest.backingFile)).append('\n');
            sb.append("Backup of destination: ").append(backup != null ? backup.getFileName() : "(none)");
            sb.append(warn);
            return ToolResult.success(sb.toString());
        } catch (NbtToolSupport.NbtToolException e) {
            return ToolResult.failure(e.getMessage());
        } catch (Throwable e) {
            return ToolResult.failure("Failed to transfer inventory: " + e);
        }
    }

    private static boolean asBool(@Nullable Object v) {
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return v instanceof String && "true".equalsIgnoreCase(((String) v).trim());
    }
}
