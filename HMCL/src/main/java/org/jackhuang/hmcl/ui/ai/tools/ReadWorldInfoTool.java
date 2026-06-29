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
import org.glavo.nbt.tag.DoubleTag;
import org.glavo.nbt.tag.ListTag;
import org.glavo.nbt.tag.Tag;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.World;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/// Summarises a single-player world: name, Minecraft version, seed, difficulty/hardcore, last
/// played, and (if available) the owner player's health, food, XP level and coordinates.
///
/// Reuses HMCL's own [`World`] reader for the level/player data so version quirks are handled
/// consistently. READ-ONLY.
///
/// Permission level: READ_ONLY.
@NotNullByDefault
public final class ReadWorldInfoTool implements Tool {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override
    public String getName() {
        return "read_world_info";
    }

    @Override
    public String getDescription() {
        return "Summarises a single-player world: name, Minecraft version, seed, difficulty, hardcore flag, last "
                + "played time, and the owner player's health/food/XP/coordinates when present. READ-ONLY. "
                + "Parameters: instance (optional, defaults to selected); world (required, the world folder name "
                + "under saves/).";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        try {
            Profile profile = NbtToolSupport.requireProfile();
            String instance = NbtToolSupport.requireInstance(profile, parameters);
            Path savesDir = NbtToolSupport.savesDir(profile, instance);

            String worldName = NbtToolSupport.str(parameters, "world");
            Path worldDir = NbtToolSupport.resolveWorldDir(savesDir, worldName);

            World world;
            try {
                world = new World(worldDir);
            } catch (Throwable e) {
                return ToolResult.failure("Failed to read world '" + worldName + "': " + e.getMessage());
            }

            StringBuilder sb = new StringBuilder();
            sb.append("World: ").append(emptyToDash(world.getWorldName())).append("  (folder: ").append(world.getFileName()).append(")\n");

            GameVersionNumber version = world.getGameVersion();
            sb.append("Minecraft version: ").append(version != null ? version.toString() : "(unknown)").append('\n');

            Long seed = world.getSeed();
            sb.append("Seed: ").append(seed != null ? seed.toString() : "(unknown)").append('\n');

            CompoundTag levelData = world.getLevelData();
            if (levelData.get("Data") instanceof CompoundTag data) {
                Byte difficulty = data.getByteOrNull("Difficulty");
                if (difficulty != null) {
                    sb.append("Difficulty: ").append(difficultyName(difficulty));
                    Byte locked = data.getByteOrNull("DifficultyLocked");
                    if (locked != null && locked != 0) {
                        sb.append(" (locked)");
                    }
                    sb.append('\n');
                }
                Byte hardcore = data.getByteOrNull("hardcore");
                if (hardcore != null) {
                    sb.append("Hardcore: ").append(hardcore != 0 ? "yes" : "no").append('\n');
                }
                Byte allowCommands = data.getByteOrNull("allowCommands");
                if (allowCommands != null) {
                    sb.append("Cheats: ").append(allowCommands != 0 ? "on" : "off").append('\n');
                }
            }

            long lastPlayed = world.getLastPlayed();
            if (lastPlayed > 0) {
                sb.append("Last played: ").append(TIME.format(Instant.ofEpochMilli(lastPlayed))).append('\n');
            }

            sb.append("World currently in use (locked): ").append(world.isLocked() ? "yes" : "no").append('\n');

            CompoundTag player = world.getPlayerData();
            if (player != null) {
                sb.append("\nOwner player:\n");
                Float health = player.getFloatOrNull("Health");
                if (health != null) {
                    sb.append("  Health: ").append(health).append(" / 20\n");
                }
                Integer food = player.getIntOrNull("foodLevel");
                if (food != null) {
                    sb.append("  Food: ").append(food).append(" / 20\n");
                }
                Integer xpLevel = player.getIntOrNull("XpLevel");
                if (xpLevel != null) {
                    sb.append("  XP level: ").append(xpLevel).append('\n');
                }
                Integer gameType = player.getIntOrNull("playerGameType");
                if (gameType != null) {
                    sb.append("  Game mode: ").append(gameModeName(gameType)).append('\n');
                }
                String pos = formatPos(player.get("Pos"));
                if (pos != null) {
                    sb.append("  Position: ").append(pos).append('\n');
                }
                Integer dim = player.getIntOrNull("Dimension");
                String dimStr = player.getStringOrNull("Dimension");
                if (dimStr != null) {
                    sb.append("  Dimension: ").append(dimStr).append('\n');
                } else if (dim != null) {
                    sb.append("  Dimension: ").append(dim).append('\n');
                }
            } else {
                sb.append("\n(No owner player data found in this world.)\n");
            }

            return ToolResult.success(sb.toString().trim());
        } catch (NbtToolSupport.NbtToolException e) {
            return ToolResult.failure(e.getMessage());
        } catch (Throwable e) {
            return ToolResult.failure("Failed to read world info: " + e);
        }
    }

    private static @Nullable String formatPos(@Nullable Tag posTag) {
        if (!(posTag instanceof ListTag<?> pos) || pos.size() < 3) {
            return null;
        }
        if (pos.getTag(0) instanceof DoubleTag x
                && pos.getTag(1) instanceof DoubleTag y
                && pos.getTag(2) instanceof DoubleTag z) {
            return String.format("x=%.1f, y=%.1f, z=%.1f", x.get(), y.get(), z.get());
        }
        return null;
    }

    private static String difficultyName(byte d) {
        switch (d) {
            case 0: return "Peaceful";
            case 1: return "Easy";
            case 2: return "Normal";
            case 3: return "Hard";
            default: return "Unknown(" + d + ")";
        }
    }

    private static String gameModeName(int g) {
        switch (g) {
            case 0: return "Survival";
            case 1: return "Creative";
            case 2: return "Adventure";
            case 3: return "Spectator";
            default: return "Unknown(" + g + ")";
        }
    }

    private static String emptyToDash(String s) {
        return s == null || s.isEmpty() ? "(unnamed)" : s;
    }
}
