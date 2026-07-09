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
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// Toggles "version isolation" (a per-instance override of the running/game directory) for a
/// single Minecraft instance — the AI-facing gap a real trace surfaced: `InstallModTool` already
/// correctly resolves the isolation-aware mods path via `HMCLGameRepository#getRunDirectory`, but
/// nothing previously let the model actually FLIP the isolation switch itself.
///
/// Mirrors the exact mutation HMCL's native settings UI performs
/// (`GameSettingsPage`'s running-directory inherit toggle):
/// - enabling: `instance.runningDirectoryProperty().setValue("")` +
///   `instance.getOverrideProperties().add(GameSettings.PROPERTY_RUNNING_DIRECTORY)` — the
///   instance now runs out of its own version root instead of the shared/global directory;
/// - disabling: `instance.getOverrideProperties().remove(GameSettings.PROPERTY_RUNNING_DIRECTORY)`
///   — the instance follows the global (or parent preset's) setting again.
///
/// A modpack-imported instance always runs isolated regardless of this override
/// ({@link HMCLGameRepository#isModpack}) — reported rather than silently no-op'd.
///
/// Permission level: it WRITES the instance's HMCL game-settings file.
@NotNullByDefault
public final class SetInstanceIsolationTool implements Tool {

    @Override
    public String getName() {
        return "set_instance_isolation";
    }

    @Override
    public String getDescription() {
        return "Toggles per-instance 'version isolation' — whether this instance's mods/saves/config/resourcepacks "
                + "live in its OWN version folder (isolated) or follow the global/parent preset's own directory "
                + "setting instead (not isolated) — commonly the shared base .minecraft, but check the actual "
                + "global default. Parameters: enable (required boolean: true = isolate "
                + "this instance, false = follow the global default), instance (optional, the instance id; "
                + "defaults to the currently selected instance). WRITES the instance's HMCL game-settings file. "
                + "If 'enable' is omitted it only REPORTS the current isolation state. "
                + "A modpack-imported instance is always isolated regardless of this setting.";
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
            return ToolResult.failure("The game repository is not loaded yet; please try again in a moment.");
        }

        // NOTE: generic aliases are NOT accepted: 'enable' is the only other parameter and must
        // not be stolen by the instance resolver's 'query' fallback.
        InstanceToolSupport.ResolvedInstance target =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (target.failure() != null) {
            return target.failure();
        }
        final String instance = target.name();

        if (repository.isModpack(instance)) {
            return ToolResult.success("Instance '" + instance + "' was imported from a modpack, so it always runs "
                    + "isolated in its own version folder regardless of this setting — nothing to change.");
        }

        GameSettings.Instance currentSetting = repository.getInstanceGameSettingsOrCreate(instance);
        if (currentSetting == null) {
            return ToolResult.failure("Could not obtain a writable game-settings object for instance '"
                    + instance + "'.");
        }
        boolean currentlyIsolated = currentSetting.getOverrideProperties()
                .contains(GameSettings.PROPERTY_RUNNING_DIRECTORY);

        Object enableObj = parameters.get("enable");
        if (enableObj == null) {
            return ToolResult.success("Instance '" + instance + "' is currently "
                    + (currentlyIsolated ? "ISOLATED (uses its own version folder)."
                    : "NOT isolated (follows the global default directory).")
                    + " To change it, call this tool again with the 'enable' parameter (true/false).");
        }
        boolean enable = parseBoolean(enableObj);
        if (enable == currentlyIsolated) {
            return ToolResult.success("Instance '" + instance + "' is already "
                    + (enable ? "isolated" : "not isolated") + "; nothing to do.");
        }

        if (repository.isInstanceGameSettingsReadOnly(instance)) {
            return ToolResult.failure("The game settings of instance '" + instance + "' are read-only and cannot "
                    + "be modified. It is currently " + (currentlyIsolated ? "isolated." : "not isolated."));
        }

        // Mutate on the JavaFX thread: the settings properties may be bound to the UI and carry
        // an auto-save listener that runs on whichever thread changes the property.
        AtomicReference<ToolResult> result = new AtomicReference<>();
        Runnable task = () -> {
            try {
                GameSettings.Instance setting = repository.getInstanceGameSettingsOrCreate(instance);
                if (setting == null) {
                    result.set(ToolResult.failure("Could not obtain a writable game-settings object for instance '"
                            + instance + "'."));
                    return;
                }
                if (enable) {
                    setting.runningDirectoryProperty().setValue("");
                    setting.getOverrideProperties().add(GameSettings.PROPERTY_RUNNING_DIRECTORY);
                } else {
                    setting.getOverrideProperties().remove(GameSettings.PROPERTY_RUNNING_DIRECTORY);
                }
                repository.saveGameSettings(instance); // explicit save in addition to the auto-save listener
                result.set(ToolResult.success((enable
                        ? "Isolated instance '" + instance + "' — it now uses its own version folder for "
                        + "mods/saves/config/resourcepacks."
                        : "Instance '" + instance + "' now follows the global default directory again "
                        + "(isolation disabled).")
                        + " The change takes effect the next time the instance is launched."));
            } catch (Throwable e) {
                result.set(ToolResult.failure("Failed to set isolation for '" + instance + "': " + e.getMessage()));
            }
        };

        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            CountDownLatch latch = new CountDownLatch(1);
            try {
                Platform.runLater(() -> {
                    try {
                        task.run();
                    } finally {
                        latch.countDown();
                    }
                });
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    return ToolResult.failure("Timed out while applying the isolation setting; the UI did not respond.");
                }
            } catch (IllegalStateException e) {
                return ToolResult.failure("Cannot apply the isolation setting: the JavaFX runtime is unavailable.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.failure("Interrupted while applying the isolation setting.");
            }
        }

        ToolResult finalResult = result.get();
        return finalResult != null ? finalResult
                : ToolResult.failure("Unknown error while setting isolation for '" + instance + "'.");
    }

    private static boolean parseBoolean(@Nullable Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && "true".equalsIgnoreCase(value.toString().trim());
    }
}
