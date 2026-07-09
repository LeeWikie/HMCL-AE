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

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// A tool that sets the custom JVM/GC arguments string (e.g. `-XX:+UseG1GC`) for a single
/// Minecraft instance of the selected profile.
///
/// This reuses HMCL's per-instance game-settings system directly — the exact same path
/// [`SetInstanceMemoryTool`] uses for memory, which is itself the path the modpack importer
/// uses ([`org.jackhuang.hmcl.game.ModpackHelper`] calls `jvmOptionsProperty().setValue(...)`):
/// - [`HMCLGameRepository#getEffectiveGameSettings(String)`] to read the current effective value,
/// - [`HMCLGameRepository#getInstanceGameSettingsOrCreate(String)`] for the writable instance override,
/// - [`GameSettings#jvmOptionsProperty()`] `.setValue(...)` to set the override,
/// - [`HMCLGameRepository#saveGameSettings(String)`] to persist it.
///
/// This exists precisely so nothing ever needs to hand-edit `instance-game-settings.json` for
/// JVM args again: [`HMCLGameRepository`] keeps every loaded instance's [`GameSettings.Instance`]
/// in memory and does a full-file overwrite the next time ANY OTHER property on that same
/// instance changes (memory, isolation, …) — a raw file write to the JSON never updates that
/// in-memory copy, so it can be silently clobbered without warning on the very next unrelated
/// change. Because this tool mutates the SAME in-memory settings object HMCL itself holds, there
/// is no such race: the change is visible to HMCL immediately and survives any later save
/// triggered by another property.
///
/// The mutation is performed on the JavaFX thread (the settings properties are bound to the UI
/// and carry an auto-save listener). Instances whose settings file is read-only cannot be
/// changed and are reported instead.
///
/// Permission level: it WRITES the instance's HMCL game-settings file.
@NotNullByDefault
public final class SetInstanceJvmArgsTool implements Tool {

    @Override
    public String getName() {
        return "set_instance_jvm_args";
    }

    @Override
    public String getDescription() {
        return "Sets custom JVM/GC arguments (e.g. '-XX:+UseG1GC -XX:+ParallelRefProcEnabled') for a Minecraft "
                + "instance of the selected profile. Parameters: jvmArgs (a single space-separated argument "
                + "string; pass an empty string to clear/remove custom JVM args), "
                + "instance (optional, the instance id; defaults to the currently selected instance). "
                + "WRITES the instance's HMCL game-settings file through the same in-memory settings path HMCL "
                + "itself uses, so it does not race HMCL's own save-on-exit/save-on-change behaviour. "
                + "If jvmArgs is omitted it only REPORTS the current JVM-args setting. "
                + "Do NOT put -Xmx/-Xms here — heap size is controlled by instance(action=\"set_memory\") "
                + "instead; this parameter is for GC and other JVM tuning flags. "
                + "NEVER edit instance-game-settings.json directly for this — always use this tool.";
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

        // NOTE: generic aliases are NOT accepted: the 'jvmArgs' parameter below already
        // honours the generic 'query' fallback, and the instance resolver must not steal it.
        InstanceToolSupport.ResolvedInstance target =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (target.failure() != null) {
            return target.failure();
        }
        final String instance = target.name();

        String currentEffective;
        try {
            String value = repository.getEffectiveGameSettings(instance).get(GameSettings::jvmOptionsProperty);
            currentEffective = value != null ? value : "";
        } catch (Throwable e) {
            return ToolResult.failure("Failed to read the JVM-args setting of '" + instance + "': " + e.getMessage());
        }

        // No value supplied at all (neither 'jvmArgs' nor the 'query' fallback): degrade to a
        // read-only report. An explicitly-supplied empty string is a deliberate "clear" request,
        // not a missing parameter, so it falls through to the write path below.
        Object jvmArgsObj = InstanceToolSupport.presentOrQueryFallback(parameters, "jvmArgs");
        if (jvmArgsObj == null) {
            return ToolResult.success("Instance '" + instance + "' currently has "
                    + (currentEffective.isEmpty() ? "no custom JVM args set." : "these custom JVM args: " + currentEffective)
                    + "\nTo change it, call this tool again with the 'jvmArgs' parameter (an empty string clears it).");
        }

        String jvmArgs = jvmArgsObj.toString().trim();

        if (repository.isInstanceGameSettingsReadOnly(instance)) {
            return ToolResult.failure("The game settings of instance '" + instance + "' are read-only and cannot be "
                    + "modified. Its current JVM args: " + (currentEffective.isEmpty() ? "(none)" : currentEffective));
        }

        boolean mentionsMemoryFlag = InstanceToolSupport.mentionsHeapSizeFlag(jvmArgs);

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
                setting.jvmOptionsProperty().setValue(jvmArgs);
                // jvmOptions' effective value is only read from THIS instance when its name is
                // also listed in overrideProperties (see GameSettings.Effective#get / isOverridden)
                // — exactly the "overrideProperties gotcha" the config-hmcl skill warns hand-editors
                // about. The settings UI's own binder (IndependentSettingBinder) always pairs a
                // setValue with this registration; skipping it here would mean the value we just
                // set is persisted to disk but silently ignored at effective-settings/launch time
                // whenever the parent preset's jvmOptions differs — precisely the kind of silent
                // no-op this tool exists to prevent, so it must never be skipped.
                setting.getOverrideProperties().add(GameSettings.PROPERTY_JVM_OPTIONS);
                repository.saveGameSettings(instance); // explicit save in addition to the auto-save listener

                StringBuilder message = new StringBuilder();
                if (jvmArgs.isEmpty()) {
                    message.append("Cleared the custom JVM args of instance '").append(instance).append("'.");
                } else {
                    message.append("Set the JVM args of instance '").append(instance).append("' to: ")
                            .append(jvmArgs).append('.');
                }
                message.append(" Previous value: ")
                        .append(currentEffective.isEmpty() ? "(none)" : currentEffective).append('.');
                message.append("\nThe change takes effect the next time the instance is launched.");
                if (mentionsMemoryFlag) {
                    message.append("\nNote: this string contains a -Xmx/-Xms flag — HMCL already controls heap "
                            + "size via instance(action=\"set_memory\"); prefer that for memory and keep this "
                            + "parameter for GC/other JVM tuning flags to avoid two competing heap settings.");
                }
                result.set(ToolResult.success(message.toString()));
            } catch (Throwable e) {
                result.set(ToolResult.failure("Failed to set JVM args for '" + instance + "': " + e.getMessage()));
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
                    return ToolResult.failure("Timed out while applying the JVM-args setting; the UI did not respond.");
                }
            } catch (IllegalStateException e) {
                // JavaFX runtime not started (should not happen inside the running launcher UI).
                return ToolResult.failure("Cannot apply the JVM-args setting: the JavaFX runtime is unavailable.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.failure("Interrupted while applying the JVM-args setting.");
            }
        }

        ToolResult finalResult = result.get();
        return finalResult != null ? finalResult
                : ToolResult.failure("Unknown error while setting JVM args for '" + instance + "'.");
    }
}
