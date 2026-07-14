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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// A tool that sets a single Minecraft instance's launch-argument group: custom game arguments,
/// environment variables, and the three JVM behaviour switches (`noJVMOptions`,
/// `noOptimizingJVMOptions`, `notCheckJVM`) of the selected profile.
///
/// This deliberately does NOT touch `jvmOptions` (the custom JVM/GC argument string) — that field
/// is owned by [`SetInstanceJvmArgsTool`] (`instance(action="set_jvm_args")`). This tool only
/// covers the five fields listed above so the two tools never fight over the same property.
///
/// It reuses HMCL's per-instance game-settings system directly — the exact same path
/// [`SetInstanceMemoryTool`] / [`SetInstanceJvmArgsTool`] use:
/// - [`HMCLGameRepository#getEffectiveGameSettings(String)`] to read the current effective values,
/// - [`HMCLGameRepository#getInstanceGameSettingsOrCreate(String)`] for the writable instance override,
/// - the matching `xxxProperty().setValue(...)` to set each override,
/// - [`HMCLGameRepository#saveGameSettings(String)`] to persist them.
///
/// Every field written here is ALSO registered in the instance's `overrideProperties` set. This is
/// the load-bearing step: a property's per-instance value is only read at launch time when its
/// name is listed in `overrideProperties` (see [`GameSettings.Effective`] / `isOverridden`).
/// Skipping it — the "overrideProperties gotcha" — persists the value to disk yet silently keeps
/// launching with the inherited preset value. The native settings UI's binder
/// ([`org.jackhuang.hmcl.ui.game.IndependentSettingBinder`]) always pairs a `setValue` with this
/// registration, so this tool does the same.
///
/// Because the mutation happens on the SAME in-memory settings object HMCL itself holds (on the
/// JavaFX thread, where the settings' auto-save listener runs), there is no race with HMCL's own
/// save-on-change / save-on-exit behaviour. Instances whose settings file is read-only cannot be
/// changed and are reported instead.
///
/// Read/write duality: when NONE of the five fields is supplied the tool degrades to a read-only
/// report of every current effective value and whether this instance overrides it.
///
/// Permission level: it WRITES the instance's HMCL game-settings file.
@NotNullByDefault
public final class SetInstanceLaunchArgsTool implements Tool {

    private static final String PARAM_GAME_ARGUMENTS = "gameArguments";
    private static final String PARAM_ENVIRONMENT_VARIABLES = "environmentVariables";
    private static final String PARAM_NO_JVM_OPTIONS = "noJVMOptions";
    private static final String PARAM_NO_OPTIMIZING_JVM_OPTIONS = "noOptimizingJVMOptions";
    private static final String PARAM_NOT_CHECK_JVM = "notCheckJVM";

    @Override
    public String getName() {
        return "set_instance_launch_args";
    }

    @Override
    public String getDescription() {
        return "Sets a Minecraft instance's launch-argument group for the selected profile. Every parameter is "
                + "OPTIONAL and independent; supply only the ones you want to change. Parameters: "
                + "gameArguments (a string of extra arguments appended to the Minecraft command line, e.g. "
                + "'--width 1280 --height 720'; pass an empty string to clear it), "
                + "environmentVariables (a string of environment variables for the game process, in HMCL's "
                + "'KEY=VALUE' newline/semicolon-separated form; pass an empty string to clear it), "
                + "noJVMOptions (boolean; true = do NOT add HMCL's generated default JVM arguments), "
                + "noOptimizingJVMOptions (boolean; true = do NOT add HMCL's optimizing JVM arguments — has no "
                + "additional effect while noJVMOptions is true), "
                + "notCheckJVM (boolean; true = skip HMCL's JVM validity check before launch — advanced, can let "
                + "the game try to start on an unsuitable Java and fail). "
                + "instance (optional, the instance id; defaults to the currently selected instance). "
                + "Each supplied field is written as a per-instance OVERRIDE of the parent preset so it actually "
                + "takes effect at launch. WRITES the instance's HMCL game-settings file. "
                + "If NO field is supplied it only REPORTS the current effective values and whether they are "
                + "overridden. Does NOT set custom JVM/GC flags (-XX:...): use instance(action=\"set_jvm_args\") "
                + "for that, and instance(action=\"set_memory\") for heap size. "
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

        // Generic aliases are NOT accepted: this tool has several named parameters, so a bare
        // 'query'/'name' key can't be unambiguously mapped to one of them and must not be stolen
        // by the instance resolver either.
        InstanceToolSupport.ResolvedInstance target =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (target.failure() != null) {
            return target.failure();
        }
        final String instance = target.name();

        // Current effective values (used both for the read-only report and as the "previous value"
        // shown after a write).
        final String currentGameArgs;
        final String currentEnvVars;
        final boolean currentNoJVM;
        final boolean currentNoOptimizing;
        final boolean currentNotCheckJVM;
        try {
            GameSettings.Effective eff = repository.getEffectiveGameSettings(instance);
            currentGameArgs = orEmpty(eff.get(GameSettings::gameArgumentsProperty));
            currentEnvVars = orEmpty(eff.get(GameSettings::environmentVariablesProperty));
            currentNoJVM = Boolean.TRUE.equals(eff.getInheritable(GameSettings::noJVMOptionsProperty));
            currentNoOptimizing = Boolean.TRUE.equals(eff.getInheritable(GameSettings::noOptimizingJVMOptionsProperty));
            currentNotCheckJVM = Boolean.TRUE.equals(eff.getInheritable(GameSettings::notCheckJVMProperty));
        } catch (Throwable e) {
            return ToolResult.failure("Failed to read the launch-args settings of '" + instance + "': " + e.getMessage());
        }

        // Parse each supplied field. A key counts as "supplied" only when present AND non-null:
        // this tolerates both adapters that omit unset keys and adapters that pass them as null.
        // An explicit empty string on a text field IS a supplied value (a deliberate clear).
        final boolean hasGameArgs = provided(parameters, PARAM_GAME_ARGUMENTS);
        final boolean hasEnvVars = provided(parameters, PARAM_ENVIRONMENT_VARIABLES);
        final boolean hasNoJVM = provided(parameters, PARAM_NO_JVM_OPTIONS);
        final boolean hasNoOptimizing = provided(parameters, PARAM_NO_OPTIMIZING_JVM_OPTIONS);
        final boolean hasNotCheckJVM = provided(parameters, PARAM_NOT_CHECK_JVM);

        // No field at all: degrade to a read-only report.
        if (!hasGameArgs && !hasEnvVars && !hasNoJVM && !hasNoOptimizing && !hasNotCheckJVM) {
            GameSettings.Instance existing = repository.getInstanceGameSettings(instance);
            StringBuilder report = new StringBuilder();
            report.append("Launch-args settings of instance '").append(instance).append("' (current effective values):\n");
            report.append("  gameArguments         : ").append(displayString(currentGameArgs))
                    .append(overrideSuffix(existing, GameSettings.PROPERTY_GAME_ARGS)).append('\n');
            report.append("  environmentVariables  : ").append(displayString(currentEnvVars))
                    .append(overrideSuffix(existing, GameSettings.PROPERTY_ENVIRONMENT_VARIABLES)).append('\n');
            report.append("  noJVMOptions          : ").append(currentNoJVM)
                    .append(overrideSuffix(existing, GameSettings.PROPERTY_NO_JVM_OPTIONS)).append('\n');
            report.append("  noOptimizingJVMOptions: ").append(currentNoOptimizing)
                    .append(overrideSuffix(existing, GameSettings.PROPERTY_NO_OPTIMIZING_JVM_OPTIONS)).append('\n');
            report.append("  notCheckJVM           : ").append(currentNotCheckJVM)
                    .append(overrideSuffix(existing, GameSettings.PROPERTY_NOT_CHECK_JVM)).append('\n');
            report.append("To change any of these, call this tool again with the matching parameter(s). "
                    + "This does not cover custom JVM/GC flags (set_jvm_args) or heap size (set_memory).");
            return ToolResult.success(report.toString());
        }

        // Parse + validate the supplied fields (outside the FX thread).
        final String newGameArgs = hasGameArgs ? parameters.get(PARAM_GAME_ARGUMENTS).toString().trim() : null;
        final String newEnvVars = hasEnvVars ? parameters.get(PARAM_ENVIRONMENT_VARIABLES).toString().trim() : null;

        final Boolean newNoJVM;
        if (hasNoJVM) {
            newNoJVM = parseBool(parameters.get(PARAM_NO_JVM_OPTIONS));
            if (newNoJVM == null) {
                return ToolResult.failure("Parameter 'noJVMOptions' must be a boolean (true/false), got: "
                        + parameters.get(PARAM_NO_JVM_OPTIONS));
            }
        } else {
            newNoJVM = null;
        }

        final Boolean newNoOptimizing;
        if (hasNoOptimizing) {
            newNoOptimizing = parseBool(parameters.get(PARAM_NO_OPTIMIZING_JVM_OPTIONS));
            if (newNoOptimizing == null) {
                return ToolResult.failure("Parameter 'noOptimizingJVMOptions' must be a boolean (true/false), got: "
                        + parameters.get(PARAM_NO_OPTIMIZING_JVM_OPTIONS));
            }
        } else {
            newNoOptimizing = null;
        }

        final Boolean newNotCheckJVM;
        if (hasNotCheckJVM) {
            newNotCheckJVM = parseBool(parameters.get(PARAM_NOT_CHECK_JVM));
            if (newNotCheckJVM == null) {
                return ToolResult.failure("Parameter 'notCheckJVM' must be a boolean (true/false), got: "
                        + parameters.get(PARAM_NOT_CHECK_JVM));
            }
        } else {
            newNotCheckJVM = null;
        }

        if (repository.isInstanceGameSettingsReadOnly(instance)) {
            return ToolResult.failure("The game settings of instance '" + instance + "' are read-only and cannot be "
                    + "modified.");
        }

        // Mutate on the JavaFX thread: the settings properties may be bound to the UI and carry an
        // auto-save listener that runs on whichever thread changes the property.
        AtomicReference<ToolResult> result = new AtomicReference<>();
        Runnable task = () -> {
            try {
                GameSettings.Instance setting = repository.getInstanceGameSettingsOrCreate(instance);
                if (setting == null) {
                    result.set(ToolResult.failure("Could not obtain a writable game-settings object for instance '"
                            + instance + "'."));
                    return;
                }

                List<String> lines = new ArrayList<>();
                if (hasGameArgs) {
                    setting.gameArgumentsProperty().setValue(newGameArgs);
                    setting.getOverrideProperties().add(GameSettings.PROPERTY_GAME_ARGS);
                    lines.add("  gameArguments         : " + displayString(newGameArgs)
                            + " (was " + displayString(currentGameArgs) + ")");
                }
                if (hasEnvVars) {
                    setting.environmentVariablesProperty().setValue(newEnvVars);
                    setting.getOverrideProperties().add(GameSettings.PROPERTY_ENVIRONMENT_VARIABLES);
                    lines.add("  environmentVariables  : " + displayString(newEnvVars)
                            + " (was " + displayString(currentEnvVars) + ")");
                }
                if (hasNoJVM) {
                    setting.noJVMOptionsProperty().setValue(newNoJVM);
                    setting.getOverrideProperties().add(GameSettings.PROPERTY_NO_JVM_OPTIONS);
                    lines.add("  noJVMOptions          : " + newNoJVM + " (was " + currentNoJVM + ")");
                }
                if (hasNoOptimizing) {
                    setting.noOptimizingJVMOptionsProperty().setValue(newNoOptimizing);
                    setting.getOverrideProperties().add(GameSettings.PROPERTY_NO_OPTIMIZING_JVM_OPTIONS);
                    lines.add("  noOptimizingJVMOptions: " + newNoOptimizing + " (was " + currentNoOptimizing + ")");
                }
                if (hasNotCheckJVM) {
                    setting.notCheckJVMProperty().setValue(newNotCheckJVM);
                    setting.getOverrideProperties().add(GameSettings.PROPERTY_NOT_CHECK_JVM);
                    lines.add("  notCheckJVM           : " + newNotCheckJVM + " (was " + currentNotCheckJVM + ")");
                }

                repository.saveGameSettings(instance); // explicit save in addition to the auto-save listener

                StringBuilder message = new StringBuilder();
                message.append("Updated launch-args settings of instance '").append(instance).append("':\n");
                message.append(String.join("\n", lines)).append('\n');

                // Mirror the native UI, where the noOptimizingJVMOptions toggle is disabled while
                // noJVMOptions is effectively on (disabling ALL default JVM args already excludes the
                // optimizing subset). Only an advisory — the value is still stored.
                boolean effectiveNoJVM = hasNoJVM ? newNoJVM : currentNoJVM;
                if (effectiveNoJVM && hasNoOptimizing) {
                    message.append("Note: noJVMOptions is on, so noOptimizingJVMOptions has no additional effect "
                            + "(all default JVM arguments are already excluded).\n");
                }

                message.append("The change takes effect the next time the instance is launched.");
                result.set(ToolResult.success(message.toString()));
            } catch (Throwable e) {
                result.set(ToolResult.failure("Failed to set launch args for '" + instance + "': " + e.getMessage()));
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
                    return ToolResult.failure("Timed out while applying the launch-args settings; the UI did not respond.");
                }
            } catch (IllegalStateException e) {
                // JavaFX runtime not started (should not happen inside the running launcher UI).
                return ToolResult.failure("Cannot apply the launch-args settings: the JavaFX runtime is unavailable.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.failure("Interrupted while applying the launch-args settings.");
            }
        }

        ToolResult finalResult = result.get();
        return finalResult != null ? finalResult
                : ToolResult.failure("Unknown error while setting launch args for '" + instance + "'.");
    }

    /// A parameter counts as supplied only when the key is present and its value is non-null.
    private static boolean provided(Map<String, Object> parameters, String key) {
        return parameters.containsKey(key) && parameters.get(key) != null;
    }

    /// Parses a boolean parameter strictly: accepts a {@link Boolean} or the case-insensitive
    /// strings {@code "true"}/{@code "false"}. Returns {@code null} for anything else so the caller
    /// can report a clear validation error.
    private static @Nullable Boolean parseBool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        String s = value.toString().trim().toLowerCase(Locale.ROOT);
        if (s.equals("true")) {
            return Boolean.TRUE;
        }
        if (s.equals("false")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static String orEmpty(@Nullable String value) {
        return value != null ? value : "";
    }

    /// Renders a free-text field value for messages: {@code (none)} when empty, otherwise the value
    /// wrapped so surrounding punctuation stays readable.
    private static String displayString(String value) {
        return value.isEmpty() ? "(none)" : "'" + value + "'";
    }

    private static String overrideSuffix(@Nullable GameSettings.Instance existing, String property) {
        boolean overridden = existing != null && existing.getOverrideProperties().contains(property);
        return overridden ? "  [overridden by this instance]" : "  [inherited from preset]";
    }
}
