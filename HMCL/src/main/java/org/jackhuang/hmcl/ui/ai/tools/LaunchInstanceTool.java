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

import javafx.application.Platform;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.ui.versions.Versions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/// A dangerous-write tool that launches a Minecraft instance.
///
/// This reuses HMCL's launcher entry point directly:
/// - [`Profiles#getSelectedProfile()`] for the active profile,
/// - [`Profiles#getSelectedInstance()`] as the default instance,
/// - [`Versions#launch(Profile, String, java.util.function.Consumer[])`] for the
///   actual launch (which builds a `LauncherHelper`, prompts for an account if
///   required, and runs the launch task asynchronously).
///
/// Because [`Versions#launch`] must run on the JavaFX Application Thread (it
/// shows dialogs and a progress pane), this tool dispatches the launch via
/// [`Platform#runLater`] and returns immediately, reporting that the launch was
/// started. It does not wait for the game to finish loading.
///
/// Permission level: DANGEROUS_WRITE. It starts an external process (the game).
@NotNullByDefault
public final class LaunchInstanceTool implements Tool {

    @Override
    public String getName() {
        return "launch_instance";
    }

    @Override
    public String getDescription() {
        return "Launches a Minecraft instance of the selected profile. "
                + "Parameters: instance (string, optional: the instance/version id to launch; "
                + "defaults to the currently selected instance). "
                + "Dispatches the launch on the UI thread (an account or download prompt may appear) "
                + "and returns immediately once launching has started. This starts the game process.";
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
        if (!repository.isLoaded()) {
            try {
                repository.refreshVersions();
            } catch (Throwable e) {
                return ToolResult.failure("Failed to load installed instances: " + e.getMessage());
            }
        }

        String instance = extractString(parameters, "instance", null);
        if (instance == null) {
            instance = extractString(parameters, "version", null); // accept alias
        }
        if (instance == null) {
            instance = Profiles.getSelectedInstance();
        }
        if (instance == null || instance.isBlank()) {
            return ToolResult.failure("No instance specified and no instance is currently selected.");
        }

        if (!repository.hasVersion(instance)) {
            return ToolResult.failure("Instance '" + instance + "' does not exist in the selected profile. "
                    + "Use list_instances to see available instances.");
        }

        final String id = instance;
        try {
            Platform.runLater(() -> Versions.launch(profile, id));
        } catch (Throwable e) {
            return ToolResult.failure("Failed to dispatch launch for '" + id + "': " + e.getMessage());
        }

        return ToolResult.success("Launching instance '" + id + "'. "
                + "The launch is starting on the launcher UI; an account selection or download prompt may appear. "
                + "Check the game window or logs for progress.");
    }

    @Nullable
    private static String extractString(Map<String, Object> params, String key, @Nullable String fallback) {
        Object val = params.get(key);
        if (val instanceof String s && !s.isEmpty()) {
            return s;
        }
        return fallback;
    }
}
