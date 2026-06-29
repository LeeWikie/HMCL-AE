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
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// A tool that sets a single option in a Minecraft instance's `options.txt`
/// (the in-game settings file, with `key:value` lines). It reads the file,
/// updates or appends the requested line while preserving all other lines, then
/// writes it back.
///
/// This reuses HMCL's launcher APIs directly:
/// - [`Profiles#getSelectedInstance()`] for the default instance id,
/// - [`Profile#getRepository()`] / [`HMCLGameRepository#getRunDirectory(String)`]
///   for the isolation-aware per-instance run directory.
///
/// Permission level: it WRITES `options.txt`. The game overwrites this file on
/// exit, so changes must be made while the game is closed.
@NotNullByDefault
public final class SetGameOptionTool implements Tool {

    @Override
    public String getName() {
        return "set_game_option";
    }

    @Override
    public String getDescription() {
        return "Sets a single option in a Minecraft instance's in-game settings file 'options.txt' (lines of 'key:value'). "
                + "Parameters: key (required), value (required), "
                + "instance (optional, the instance id; defaults to the currently selected instance). "
                + "Common keys: renderDistance, maxFps, fov, guiScale, lang. "
                + "WRITES the file: the matching line is updated (or appended if absent) while all other lines are kept. "
                + "Note: Minecraft overwrites options.txt when it exits, so set options while the game is CLOSED.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object keyObj = parameters.get("key");
        if (!(keyObj instanceof String) || ((String) keyObj).trim().isEmpty()) {
            return ToolResult.failure("Parameter 'key' is required.");
        }
        String key = ((String) keyObj).trim();

        Object valueObj = parameters.get("value");
        if (valueObj == null) {
            return ToolResult.failure("Parameter 'value' is required.");
        }
        String value = String.valueOf(valueObj);

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

        Path optionsFile;
        try {
            optionsFile = repository.getRunDirectory(instance).resolve("options.txt");
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve the run directory of '" + instance + "': " + e.getMessage());
        }

        if (!Files.isRegularFile(optionsFile)) {
            return ToolResult.failure("'options.txt' has not been generated for instance '" + instance + "'. "
                    + "Start the game at least once first. Expected at: " + optionsFile);
        }

        List<String> lines;
        try {
            lines = new ArrayList<>(Files.readAllLines(optionsFile, StandardCharsets.UTF_8));
        } catch (Throwable e) {
            return ToolResult.failure("Failed to read 'options.txt': " + e.getMessage());
        }

        @Nullable String oldValue = null;
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int idx = line.indexOf(':');
            if (idx > 0 && line.substring(0, idx).equals(key)) {
                oldValue = line.substring(idx + 1);
                lines.set(i, key + ":" + value);
                found = true;
                break;
            }
        }
        if (!found) {
            lines.add(key + ":" + value);
        }

        try {
            Files.write(optionsFile, lines, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return ToolResult.failure("Failed to write 'options.txt': " + e.getMessage());
        }

        String change = found
                ? "'" + oldValue + "' -> '" + value + "'"
                : "(not set) -> '" + value + "' (new option added)";
        return ToolResult.success("Set option '" + key + "' on instance '" + instance + "': " + change + "\n"
                + "File: " + optionsFile + "\n"
                + "Note: if the game is running, this change will be overwritten when it exits.");
    }
}
