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
import java.util.function.IntSupplier;

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
/// Before dispatching the launch, it consumes any {@link WorldBackupManager}
/// pending-first-launch markers for this instance (see
/// {@link WorldBackupManager#consumePendingFirstLaunchBackups}) — this is the other half of
/// the safety net {@link ImportWorldTool} sets up for a freshly-imported world, so an
/// old/incompatible save gets one automatic snapshot before Minecraft ever touches it.
///
/// Permission level: DANGEROUS_WRITE. It starts an external process (the game).
@NotNullByDefault
public final class LaunchInstanceTool implements Tool {

    private final IntSupplier worldBackupRetention;

    /// @param worldBackupRetention supplies the current retention count (from AI settings),
    ///                             applied to the automatic pre-launch safety backup of any
    ///                             freshly-imported world (see {@link WorldBackupManager}).
    public LaunchInstanceTool(IntSupplier worldBackupRetention) {
        this.worldBackupRetention = worldBackupRetention;
    }

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
                + "and returns immediately once launching has started. This starts the game process. "
                + "If any world in this instance was imported since its last launch, an automatic safety "
                + "backup of that world is taken first (see worlds_import).";
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
                    + "Use game(action=\"list\") (or instance(action=\"list\")) to see available instances.");
        }

        final String id = instance;

        // Safety net: back up any world imported into this instance since its last launch,
        // BEFORE Minecraft gets a chance to touch (and potentially corrupt) it. Best-effort —
        // a failed safety backup must never block the user from playing.
        WorldBackupManager.PendingBackupResult pendingBackups =
                WorldBackupManager.consumePendingFirstLaunchBackups(id, worldBackupRetention.getAsInt());

        try {
            Platform.runLater(() -> Versions.launch(profile, id));
        } catch (Throwable e) {
            return ToolResult.failure("Failed to dispatch launch for '" + id + "': " + e.getMessage());
        }

        StringBuilder message = new StringBuilder("Launching instance '").append(id).append("'. ")
                .append("The launch is starting on the launcher UI; an account selection or download prompt may appear. ")
                .append("Check the game window or logs for progress.");
        if (!pendingBackups.backedUpWorlds().isEmpty()) {
            message.append("\nAutomatic pre-launch safety backup taken for freshly-imported world(s): ")
                    .append(String.join(", ", pendingBackups.backedUpWorlds())).append('.');
        }
        if (!pendingBackups.failedWorlds().isEmpty()) {
            message.append("\nWARNING: could not take the automatic safety backup for world(s): ")
                    .append(String.join(", ", pendingBackups.failedWorlds()))
                    .append(" — consider running instance(action=\"worlds_backup_create\") for them manually.");
        }
        return ToolResult.success(message.toString());
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
