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
import java.util.List;
import java.util.Map;

/// A read-only tool that reads a Minecraft instance's `options.txt` (the in-game
/// settings file, with `key:value` lines) and returns all options or a single key.
///
/// This reuses HMCL's launcher APIs directly:
/// - [`Profiles#getSelectedInstance()`] for the default instance id,
/// - [`Profile#getRepository()`] / [`HMCLGameRepository#getRunDirectory(String)`]
///   for the isolation-aware per-instance run directory.
///
/// Permission level: READ_ONLY. It never modifies any file.
@NotNullByDefault
public final class ReadGameOptionsTool implements Tool {

    @Override
    public String getName() {
        return "read_game_options";
    }

    @Override
    public String getDescription() {
        return "Reads a Minecraft instance's in-game settings file 'options.txt' (lines of 'key:value'). "
                + "Parameters: instance (optional, the instance id; defaults to the currently selected instance), "
                + "key (optional; if given, only that option's value is returned, otherwise all options are listed). "
                + "Read-only. If 'options.txt' does not exist, the game has not been started yet for this instance.";
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
            lines = Files.readAllLines(optionsFile, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return ToolResult.failure("Failed to read 'options.txt': " + e.getMessage());
        }

        Object keyObj = parameters.get("key");
        @Nullable String wantedKey = (keyObj instanceof String && !((String) keyObj).trim().isEmpty())
                ? ((String) keyObj).trim() : null;

        if (wantedKey != null) {
            for (String line : lines) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String k = line.substring(0, idx);
                    if (k.equals(wantedKey)) {
                        return ToolResult.success(wantedKey + " = " + line.substring(idx + 1));
                    }
                }
            }
            return ToolResult.failure("Option '" + wantedKey + "' was not found in 'options.txt' of instance '"
                    + instance + "'.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("options.txt of instance '").append(instance).append("':\n");
        int count = 0;
        for (String line : lines) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                sb.append("  ").append(line.substring(0, idx)).append(" = ").append(line.substring(idx + 1)).append('\n');
                count++;
            }
        }
        if (count == 0) {
            return ToolResult.success("'options.txt' of instance '" + instance + "' contains no key:value options.");
        }
        return ToolResult.success(sb.toString().trim());
    }
}
