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

/// A tool that sets the maximum JVM heap memory (-Xmx, in MiB) for a single
/// Minecraft instance of the selected profile.
///
/// This reuses HMCL's per-instance game-settings system directly — the same path
/// the modpack importer uses ([`ModpackHelper`] calls `maxMemoryProperty().setValue(...)`):
/// - [`HMCLGameRepository#getEffectiveGameSettings(String)`] to read the current effective value,
/// - [`HMCLGameRepository#getInstanceGameSettingsOrCreate(String)`] for the writable instance override,
/// - [`GameSettings#maxMemoryProperty()`] `.setValue(...)` to set the override,
/// - [`HMCLGameRepository#saveGameSettings(String)`] to persist it.
///
/// Because we mutate the SAME in-memory settings object HMCL itself holds, this value
/// is also what HMCL writes on exit, so there is no conflict with HMCL's on-exit save.
/// The mutation is performed on the JavaFX thread (the settings properties are bound to
/// the UI and carry an auto-save listener). Instances whose settings file is read-only
/// cannot be changed and are reported instead.
///
/// Permission level: it WRITES the instance's HMCL game-settings file.
@NotNullByDefault
public final class SetInstanceMemoryTool implements Tool {

    @Override
    public String getName() {
        return "set_instance_memory";
    }

    @Override
    public String getDescription() {
        return "Sets the maximum JVM heap memory (-Xmx, in MiB) for a Minecraft instance of the selected profile. "
                + "Parameters: maxMemoryMB (required, an integer number of megabytes, e.g. 4096 for 4 GB), "
                + "instance (optional, the instance id; defaults to the currently selected instance). "
                + "WRITES the instance's HMCL game-settings file. "
                + "If maxMemoryMB is omitted it only REPORTS the current memory setting. "
                + "Note: this controls HMCL's launch memory, not in-game options.";
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

        int currentEffective;
        try {
            currentEffective = repository.getEffectiveGameSettings(instance).getMaxMemory();
        } catch (Throwable e) {
            return ToolResult.failure("Failed to read the memory setting of '" + instance + "': " + e.getMessage());
        }

        // No value supplied: degrade to a read-only report.
        Object memoryObj = parameters.get("maxMemoryMB");
        if (memoryObj == null) {
            memoryObj = parameters.get("query");
        }
        if (memoryObj == null || String.valueOf(memoryObj).trim().isEmpty()) {
            return ToolResult.success("Instance '" + instance + "' is currently configured with a maximum heap of "
                    + currentEffective + " MiB (-Xmx" + currentEffective + "m).\n"
                    + "To change it, call this tool again with the 'maxMemoryMB' parameter.");
        }

        int maxMemoryMB = InstanceToolSupport.parseInt(memoryObj, Integer.MIN_VALUE);
        if (maxMemoryMB == Integer.MIN_VALUE) {
            return ToolResult.failure("Parameter 'maxMemoryMB' must be an integer number of megabytes, got: " + memoryObj);
        }
        if (maxMemoryMB < 256) {
            return ToolResult.failure("Requested maximum memory (" + maxMemoryMB + " MiB) is too small; "
                    + "use at least 256 MiB.");
        }

        if (repository.isInstanceGameSettingsReadOnly(instance)) {
            return ToolResult.failure("The game settings of instance '" + instance + "' are read-only and cannot be "
                    + "modified. Its current maximum heap is " + currentEffective + " MiB.");
        }

        // Mutate on the JavaFX thread: the settings properties may be bound to the UI and
        // carry an auto-save listener that runs on whichever thread changes the property.
        AtomicReference<ToolResult> result = new AtomicReference<>();
        Runnable task = () -> {
            try {
                GameSettings.Instance setting = repository.getInstanceGameSettingsOrCreate(instance);
                if (setting == null) {
                    result.set(ToolResult.failure("Could not obtain a writable game-settings object for instance '"
                            + instance + "'."));
                    return;
                }
                setting.maxMemoryProperty().setValue(maxMemoryMB);
                repository.saveGameSettings(instance); // explicit save in addition to the auto-save listener
                result.set(ToolResult.success("Set maximum heap memory of instance '" + instance + "' to "
                        + maxMemoryMB + " MiB (-Xmx" + maxMemoryMB + "m). Previous effective value: "
                        + currentEffective + " MiB.\nThe change takes effect the next time the instance is launched."));
            } catch (Throwable e) {
                result.set(ToolResult.failure("Failed to set memory for '" + instance + "': " + e.getMessage()));
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
                    return ToolResult.failure("Timed out while applying the memory setting; the UI did not respond.");
                }
            } catch (IllegalStateException e) {
                // JavaFX runtime not started (should not happen inside the running launcher UI).
                return ToolResult.failure("Cannot apply the memory setting: the JavaFX runtime is unavailable.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.failure("Interrupted while applying the memory setting.");
            }
        }

        ToolResult finalResult = result.get();
        return finalResult != null ? finalResult
                : ToolResult.failure("Unknown error while setting memory for '" + instance + "'.");
    }
}
