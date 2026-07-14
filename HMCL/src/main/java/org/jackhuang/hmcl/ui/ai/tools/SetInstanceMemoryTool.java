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
import org.jackhuang.hmcl.util.platform.SystemInfo;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.jackhuang.hmcl.util.DataSizeUnit.MEGABYTES;

/// A tool that controls the JVM heap memory allocation (-Xmx) for a single Minecraft
/// instance of the selected profile: either a fixed maximum (in MiB) or HMCL's automatic
/// physical-RAM-based allocation.
///
/// This reuses HMCL's per-instance game-settings system directly — the same path
/// the modpack importer uses ([`ModpackHelper`] calls `maxMemoryProperty().setValue(...)`):
/// - [`HMCLGameRepository#getEffectiveGameSettings(String)`] to read the current effective values,
/// - [`HMCLGameRepository#getInstanceGameSettingsOrCreate(String)`] for the writable instance override,
/// - [`GameSettings#maxMemoryProperty()`] / [`GameSettings#autoMemoryProperty()`] `.setValue(...)` to set the overrides,
/// - [`HMCLGameRepository#saveGameSettings(String)`] to persist them.
///
/// ### Why a fixed value must ALSO turn automatic allocation off
///
/// `maxMemory` and `autoMemory` are two independent, separately-overridable properties (HMCL's
/// native settings UI, [`IndependentSettingBinder`], gives each its own inheritance button). An
/// instance's `autoMemory` defaults to `true`. When it is effectively `true`,
/// [`HMCLGameRepository#getLaunchOptions`] does NOT launch with the configured maximum: it recomputes
/// `-Xmx` from available physical RAM ([`HMCLGameRepository#getAllocatedMemory`] with `auto=true`) and
/// treats the configured maximum only as a LOWER BOUND (`Math.max(minimum, suggested)`). So setting
/// only `maxMemory` — while leaving `autoMemory` inherited/`true` — would persist the number to disk
/// yet keep launching with an auto-sized heap, i.e. the tool would report "set to 4 GiB" while the
/// game actually starts with something else. To make a requested fixed value take effect, this tool
/// sets `maxMemory` AND sets `autoMemory=false`, adding BOTH `PROPERTY_MAX_MEMORY` and
/// `PROPERTY_AUTO_MEMORY` to `overrideProperties` (exactly what the native UI's manual-allocation path
/// does). The `auto=true` parameter is the inverse: it re-enables automatic allocation.
///
/// Every override registration mirrors the "overrideProperties gotcha" the config-hmcl skill warns
/// hand-editors about: an instance value is only read at effective/launch time when its property name
/// is also present in `overrideProperties`; without that, the value is silently ignored whenever the
/// parent preset differs.
///
/// Because we mutate the SAME in-memory settings object HMCL itself holds, these values are also what
/// HMCL writes on exit, so there is no conflict with HMCL's on-exit save. The mutation is performed on
/// the JavaFX thread (the settings properties are bound to the UI and carry an auto-save listener).
/// Instances whose settings file is read-only cannot be changed and are reported instead.
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
        return "Controls how much JVM heap memory (-Xmx) a Minecraft instance of the selected profile gets. "
                + "Parameters: maxMemoryMB (optional integer megabytes, e.g. 4096 for 4 GB) pins a FIXED maximum and "
                + "automatically turns automatic allocation OFF so the exact value takes effect; "
                + "auto (optional boolean) true switches the instance back to AUTOMATIC allocation (HMCL sizes -Xmx "
                + "from available physical RAM at launch), false forces fixed allocation while keeping the current "
                + "number; instance (optional, the instance id; defaults to the currently selected instance). "
                + "Pass maxMemoryMB OR auto=true, not both. WRITES the instance's HMCL game-settings file. "
                + "If neither maxMemoryMB nor auto is given it only REPORTS the current memory settings "
                + "(fixed vs automatic, and the effective maximum). "
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

        // NOTE: generic aliases are NOT accepted for instance resolution: the 'maxMemoryMB' parameter below
        // already honours the generic 'query' fallback, and the instance resolver must not steal it.
        InstanceToolSupport.ResolvedInstance target =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (target.failure() != null) {
            return target.failure();
        }
        final String instance = target.name();

        // Read the current effective state (used both for the read-only report and for the "previous
        // value" line of the write confirmations).
        final int currentEffective;
        final boolean currentAuto;
        try {
            GameSettings.Effective effective = repository.getEffectiveGameSettings(instance);
            currentEffective = effective.getMaxMemory();
            currentAuto = Boolean.TRUE.equals(effective.get(GameSettings::autoMemoryProperty));
        } catch (Throwable e) {
            return ToolResult.failure("Failed to read the memory setting of '" + instance + "': " + e.getMessage());
        }

        // Parse the two write parameters. 'auto' takes an explicit boolean; when it is present we do NOT
        // fall back to the generic 'query' for maxMemoryMB, so a stray natural-language query cannot be
        // misread as a memory value and trip the mutual-exclusion check below.
        Object autoObj = parameters.get("auto");
        final boolean hasAuto = autoObj != null && !String.valueOf(autoObj).trim().isEmpty();
        final @Nullable Boolean auto = hasAuto ? parseBoolean(autoObj) : null;

        Object memoryObj = parameters.get("maxMemoryMB");
        if (memoryObj == null && !hasAuto) {
            memoryObj = parameters.get("query");
        }
        final boolean hasMemory = memoryObj != null && !String.valueOf(memoryObj).trim().isEmpty();

        // No write parameter supplied: degrade to a read-only report of the current configuration.
        if (!hasMemory && !hasAuto) {
            GameSettings.Instance existing = repository.getInstanceGameSettings(instance);
            boolean maxOverridden = existing != null
                    && existing.getOverrideProperties().contains(GameSettings.PROPERTY_MAX_MEMORY);
            boolean autoOverridden = existing != null
                    && existing.getOverrideProperties().contains(GameSettings.PROPERTY_AUTO_MEMORY);
            StringBuilder sb = new StringBuilder();
            sb.append("Memory configuration of instance '").append(instance).append("':\n");
            sb.append("- Automatic allocation: ").append(currentAuto ? "ON" : "OFF")
                    .append(autoOverridden ? " (set on this instance)." : " (inherited from the preset).").append('\n');
            sb.append("- Maximum heap (-Xmx): ").append(currentEffective).append(" MiB")
                    .append(maxOverridden ? " (set on this instance).\n" : " (inherited from the preset).\n");
            if (currentAuto) {
                sb.append("Because automatic allocation is ON, HMCL sizes -Xmx from available physical RAM at launch "
                        + "and treats the configured maximum only as a lower bound, so a fixed value may not take "
                        + "effect. To pin an exact value, call this tool again with 'maxMemoryMB' (that turns automatic "
                        + "allocation off). To keep automatic allocation, leave it as is.");
            } else {
                sb.append("The instance launches with this fixed maximum. To change it, call this tool again with "
                        + "'maxMemoryMB'; to hand sizing back to HMCL, call it with auto=true.");
            }
            return ToolResult.success(sb.toString());
        }

        // Mutual exclusion: automatic allocation ignores a fixed maximum, so asking for both is ambiguous.
        if (Boolean.TRUE.equals(auto) && hasMemory) {
            return ToolResult.failure("Requested automatic allocation (auto=true) together with an explicit "
                    + "'maxMemoryMB'. These are mutually exclusive: automatic allocation ignores a fixed maximum "
                    + "(it only uses it as a lower bound). Pass 'maxMemoryMB' alone to pin a fixed heap, or auto=true "
                    + "alone to let HMCL size it from physical RAM.");
        }

        // Decide the target state.
        // - switchToAuto == true  : re-enable automatic allocation; leave the configured maximum untouched
        //   (in auto mode it is only a lower bound, mirroring the native UI which keeps the two overrides
        //   independent).
        // - switchToAuto == false : fixed allocation. targetMaxMemory is the exact value to pin — the
        //   explicit request when maxMemoryMB was given, otherwise the current effective maximum when the
        //   caller only asked to turn automatic allocation off (auto=false with no number).
        final boolean switchToAuto;
        final @Nullable Integer targetMaxMemory;

        if (Boolean.TRUE.equals(auto)) {
            switchToAuto = true;
            targetMaxMemory = null;
        } else {
            switchToAuto = false;
            if (hasMemory) {
                int maxMemoryMB = InstanceToolSupport.parseInt(memoryObj, Integer.MIN_VALUE);
                if (maxMemoryMB == Integer.MIN_VALUE) {
                    return ToolResult.failure("Parameter 'maxMemoryMB' must be an integer number of megabytes, got: "
                            + memoryObj);
                }
                if (maxMemoryMB < 256) {
                    return ToolResult.failure("Requested maximum memory (" + maxMemoryMB + " MiB) is too small; "
                            + "use at least 256 MiB.");
                }

                // Upper-bound sanity check: mirrors the same physical-RAM comparison LauncherHelper's
                // pre-launch advisory already performs (game/LauncherHelper.java, "launch.advice.not_enough_space"),
                // just moved earlier so a bogus value is rejected at set-memory time instead of only surfacing as a
                // launch-time warning after the fact. A value at or below physical RAM is always allowed (HMCL itself
                // only warns, never blocks, on a merely-tight-but-under value).
                long totalMemoryMB = (long) MEGABYTES.convertFromBytes(SystemInfo.getTotalMemorySize());
                if (totalMemoryMB > 0 && maxMemoryMB > totalMemoryMB) {
                    return ToolResult.failure("Requested maximum memory (" + maxMemoryMB + " MiB) exceeds this "
                            + "machine's physical RAM (" + totalMemoryMB + " MiB total). Setting -Xmx above physical "
                            + "RAM will make the game fail to start or thrash badly; pick a value at or below "
                            + totalMemoryMB + " MiB (leave headroom for the OS and other programs — typically no more "
                            + "than ~70-80% of total RAM).");
                }
                targetMaxMemory = maxMemoryMB;
            } else {
                // auto=false with no number: keep the current effective maximum, just pin it as fixed.
                targetMaxMemory = currentEffective;
            }
        }

        if (repository.isInstanceGameSettingsReadOnly(instance)) {
            return ToolResult.failure("The game settings of instance '" + instance + "' are read-only and cannot be "
                    + "modified. Automatic allocation is currently " + (currentAuto ? "ON" : "OFF")
                    + " and its maximum heap is " + currentEffective + " MiB.");
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

                // Always take explicit ownership of autoMemory: whether turning it on (auto path) or off
                // (fixed path), the value only takes effect at launch when PROPERTY_AUTO_MEMORY is also in
                // overrideProperties; otherwise the effective value silently follows the parent preset.
                setting.autoMemoryProperty().setValue(!switchToAuto ? Boolean.FALSE : Boolean.TRUE);
                setting.getOverrideProperties().add(GameSettings.PROPERTY_AUTO_MEMORY);

                if (targetMaxMemory != null) {
                    setting.maxMemoryProperty().setValue(targetMaxMemory);
                    setting.getOverrideProperties().add(GameSettings.PROPERTY_MAX_MEMORY);
                }

                repository.saveGameSettings(instance); // explicit save in addition to the auto-save listener

                String message;
                if (switchToAuto) {
                    message = "Switched instance '" + instance + "' to AUTOMATIC memory allocation. HMCL will now size "
                            + "-Xmx from available physical RAM at launch (any configured maximum acts only as a lower "
                            + "bound). Previous effective maximum: " + currentEffective + " MiB"
                            + (currentAuto ? " (already automatic)." : " (was a fixed maximum).");
                } else {
                    message = "Set instance '" + instance + "' to a FIXED maximum heap of " + targetMaxMemory
                            + " MiB (-Xmx" + targetMaxMemory + "m) and turned automatic allocation OFF, so this exact "
                            + "value takes effect at launch. Previous effective maximum: " + currentEffective + " MiB"
                            + (currentAuto ? " (was automatically sized)." : ".");
                }
                message += "\nThe change takes effect the next time the instance is launched.";
                result.set(ToolResult.success(message));
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

    private static boolean parseBoolean(@Nullable Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && "true".equalsIgnoreCase(value.toString().trim());
    }
}
