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
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.World;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/// A read-only tool that lists the local single-player worlds (saves) of an
/// instance, reading each world's `level.dat` for its real name, game version,
/// last-played time and lock state.
///
/// This reuses HMCL's native world API directly:
/// - [`World#getWorlds(Path)`] to enumerate and parse the `saves/` directory,
/// - [`World#getWorldName()`] / [`World#getGameVersion()`] / [`World#getLastPlayed()`]
///   / [`World#isLocked()`] for the per-world details.
///
/// This complements the existing `search_worlds` tool (which finds downloadable
/// worlds online) and `backup_world` (which copies one world): there was no way
/// for the AI to see what worlds the user already has.
///
/// Permission level: READ_ONLY. It never modifies any save.
@NotNullByDefault
public final class ListWorldsTool implements Tool {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @Override
    public String getName() {
        return "list_worlds";
    }

    @Override
    public String getDescription() {
        return "Lists the local single-player worlds (saves) of an instance, reading each world's level.dat for "
                + "its display name, Minecraft version, last-played time and whether it is currently locked (in use). "
                + "Parameter: instance (optional; defaults to the currently selected instance). Read-only. "
                + "Use this instead of ls/dir over the saves folder. To copy a world use backup_world.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Profile profile;
        try {
            profile = Profiles.getSelectedProfile();
        } catch (Throwable e) {
            return ToolResult.failure("No profile is currently selected: " + e.getMessage());
        }
        HMCLGameRepository repository = profile.getRepository();

        Object instanceObj = parameters.get("instance");
        String instance;
        if (instanceObj instanceof String && !((String) instanceObj).trim().isEmpty()) {
            instance = ((String) instanceObj).trim();
        } else {
            @Nullable String selected = Profiles.getSelectedInstance();
            if (selected == null) {
                return ToolResult.failure("No instance is selected and no 'instance' parameter was given.");
            }
            instance = selected;
        }

        try {
            if (!repository.hasVersion(instance)) {
                return ToolResult.failure("Instance '" + instance + "' does not exist in the selected profile.");
            }
        } catch (Throwable e) {
            return ToolResult.failure("Failed to verify instance '" + instance + "': " + e.getMessage());
        }

        Path savesDir;
        try {
            savesDir = repository.getRunDirectory(instance).resolve("saves");
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve the run directory of '" + instance + "': " + e.getMessage());
        }

        if (!Files.isDirectory(savesDir)) {
            return ToolResult.success("Instance '" + instance + "' has no 'saves' folder yet (no worlds). "
                    + "Expected at: " + savesDir);
        }

        List<World> worlds;
        try {
            worlds = World.getWorlds(savesDir);
        } catch (Throwable e) {
            return ToolResult.failure("Failed to read worlds: " + e.getMessage());
        }

        if (worlds.isEmpty()) {
            return ToolResult.success("No worlds found in " + savesDir + ".");
        }

        // Most-recently-played first; worlds that never report a time go last.
        worlds.sort((a, b) -> Long.compare(safeLastPlayed(b), safeLastPlayed(a)));

        StringBuilder sb = new StringBuilder();
        sb.append("Worlds of instance '").append(instance).append("' (").append(worlds.size()).append("):\n");
        for (World world : worlds) {
            String name = world.getWorldName();
            String folder = world.getFileName();
            sb.append("  - ").append(name == null || name.isEmpty() ? folder : name);
            if (!folder.equals(name)) {
                sb.append("  (folder: ").append(folder).append(')');
            }
            sb.append('\n');

            GameVersionNumber version = null;
            try {
                version = world.getGameVersion();
            } catch (Throwable ignored) {
                // Some old/odd worlds omit the version tag.
            }
            if (version != null) {
                sb.append("      version: ").append(version).append('\n');
            }

            long lastPlayed = safeLastPlayed(world);
            if (lastPlayed > 0L) {
                sb.append("      lastPlayed: ").append(TIME_FORMAT.format(Instant.ofEpochMilli(lastPlayed))).append('\n');
            }

            try {
                if (world.isLocked()) {
                    sb.append("      status: LOCKED (currently open / in use)\n");
                }
            } catch (Throwable ignored) {
                // Lock probing is best-effort.
            }
        }

        return ToolResult.success(sb.toString().trim());
    }

    private static long safeLastPlayed(World world) {
        try {
            return world.getLastPlayed();
        } catch (Throwable e) {
            return 0L;
        }
    }
}
