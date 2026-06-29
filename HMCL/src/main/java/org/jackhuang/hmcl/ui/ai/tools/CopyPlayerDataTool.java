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

import org.glavo.nbt.tag.CompoundTag;
import org.glavo.nbt.tag.Tag;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.setting.Profile;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/// Copies one player's entire saved data onto another player slot within the same world.
/// HIGH-RISK (writes to disk). This rescues the classic "I changed my account/username and
/// lost my inventory, level and position" situation in LAN/server-style worlds, where player
/// state is keyed by UUID in `playerdata/<uuid>.dat`.
///
/// The source may live in `playerdata/`, the legacy `players/data/`, or the single-player
/// owner slot in `level.dat` (`Data.Player`). The destination is always written to
/// `playerdata/<toUuid>.dat`. A username is automatically converted to its offline UUID.
///
/// Safety: the destination file is backed up first (if present), the write is atomic and
/// gzip-compressed (standard for player `.dat`), and the operation is refused while the world
/// is open in-game.
///
/// Permission level: WRITE / HIGH-RISK.
@NotNullByDefault
public final class CopyPlayerDataTool implements Tool {

    @Override
    public String getName() {
        return "copy_player_data";
    }

    @Override
    public String getDescription() {
        return "Copies ALL of one player's saved data onto another player slot in the same world (rescues "
                + "'changed account and lost my save'). WRITE / HIGH-RISK. Parameters: instance (optional); world "
                + "(required, the world folder name under saves/); from (required, source player as a UUID or "
                + "username); to (required, destination player as a UUID or username). Usernames are converted to "
                + "their offline UUID automatically (case-sensitive). Source is read from playerdata/, legacy "
                + "players/data/, or level.dat's Data.Player; destination is written to playerdata/<toUuid>.dat. "
                + "Backs up the destination first, writes atomically (gzip), and refuses if the world is open "
                + "in-game. Advise the user to close the game and back up the world.";
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
            UUID fromUuid = NbtToolSupport.resolvePlayerUuid(from);
            UUID toUuid = NbtToolSupport.resolvePlayerUuid(to);
            if (fromUuid.equals(toUuid)) {
                return ToolResult.failure("'from' and 'to' resolve to the same UUID (" + fromUuid + "); nothing to copy.");
            }

            if (NbtToolSupport.isWorldLocked(worldDir)) {
                return ToolResult.failure("World '" + world + "' is currently open in the game. Quit to the title "
                        + "screen (or close Minecraft) first, then retry.");
            }

            NbtToolSupport.PlayerHandle source = NbtToolSupport.locatePlayer(worldDir, fromUuid);
            if (source == null) {
                return ToolResult.failure("Could not find source player data for " + fromUuid + " in world '" + world
                        + "' (looked in playerdata/, players/data/ and level.dat's Data.Player).");
            }

            CompoundTag copy = source.player.clone();
            Path destFile = NbtToolSupport.playerDataFile(worldDir, toUuid);
            boolean destExisted = Files.isRegularFile(destFile);
            Path backup = null;
            if (destExisted) {
                backup = NbtToolSupport.backup(destFile);
            } else {
                try {
                    Files.createDirectories(destFile.getParent());
                } catch (Exception e) {
                    return ToolResult.failure("Failed to create playerdata directory: " + e.getMessage());
                }
            }

            // Player .dat files are gzip-compressed.
            NbtToolSupport.writeTag(destFile, (Tag) copy, true);

            StringBuilder sb = new StringBuilder();
            sb.append("Copied player data in world '").append(world).append("':\n");
            sb.append("  from: ").append(from).append("  (").append(fromUuid).append(")\n");
            sb.append("  to:   ").append(to).append("  (").append(toUuid).append(")\n");
            sb.append("  destination: playerdata/").append(toUuid).append(".dat ")
                    .append(destExisted ? "(overwritten)" : "(created)").append('\n');
            sb.append("Backup of destination: ").append(backup != null ? backup.getFileName() : "(none — file was new)");
            return ToolResult.success(sb.toString());
        } catch (NbtToolSupport.NbtToolException e) {
            return ToolResult.failure(e.getMessage());
        } catch (Throwable e) {
            return ToolResult.failure("Failed to copy player data: " + e);
        }
    }
}
