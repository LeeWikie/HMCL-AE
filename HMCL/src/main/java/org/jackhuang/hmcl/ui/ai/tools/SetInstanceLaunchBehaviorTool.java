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
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.ProcessPriority;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.setting.LauncherVisibility;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.setting.property.SettingProperty;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/// Sets the "launch behavior" group of a single Minecraft instance's HMCL game settings — the
/// options that decide what the launcher does around a game launch rather than what the game
/// itself renders:
/// - `launcherVisibility` ([LauncherVisibility]: close / hide / keep / hide_and_reopen),
/// - `processPriority` ([ProcessPriority]: low / below_normal / normal / above_normal / high),
/// - the boolean toggles `allowAutoAgent`, `disableAutoGameOptions`, `showLogs`,
///   `enableDebugLogOutput`, `notCheckGame`.
///
/// Every one of these is an [`org.jackhuang.hmcl.setting.property.InheritableProperty`], so writing
/// it correctly means BOTH setting the value AND registering the property name in the instance's
/// `overrideProperties` set — otherwise the instance keeps inheriting the parent preset's value and
/// the write is a silent no-op at launch time. This mirrors exactly what HMCL's native settings UI
/// does (`GameSettingsPage` + `IndependentSettingBinder#setOverridden`): a `setValue` is always
/// paired with an `overrideProperties.add`, and that is what the modpack importer / memory / isolation
/// tools do too (see [SetInstanceMemoryTool], [SetInstanceIsolationTool]).
///
/// Because we mutate the SAME in-memory settings object HMCL itself holds, this value is also what
/// HMCL writes on exit, so there is no conflict with HMCL's on-exit save. The mutation is performed
/// on the JavaFX thread (the settings properties are bound to the UI and carry an auto-save
/// listener). Instances whose settings file is read-only cannot be changed and are reported instead.
///
/// Each parameter is optional and only the ones actually supplied are changed. If NONE of the launch
/// behavior parameters are supplied it degrades to a read-only REPORT of the current effective values
/// and whether each is overridden by this instance or inherited from its parent preset.
///
/// Permission level: it WRITES the instance's HMCL game-settings file.
@NotNullByDefault
public final class SetInstanceLaunchBehaviorTool implements Tool {

    /// The launcher-visibility parameter key.
    private static final String KEY_LAUNCHER_VISIBILITY = "launcherVisibility";
    /// The process-priority parameter key.
    private static final String KEY_PROCESS_PRIORITY = "processPriority";
    /// The allow-auto-agent parameter key.
    private static final String KEY_ALLOW_AUTO_AGENT = "allowAutoAgent";
    /// The disable-auto-game-options parameter key.
    private static final String KEY_DISABLE_AUTO_GAME_OPTIONS = "disableAutoGameOptions";
    /// The show-logs parameter key.
    private static final String KEY_SHOW_LOGS = "showLogs";
    /// The enable-debug-log-output parameter key.
    private static final String KEY_ENABLE_DEBUG_LOG_OUTPUT = "enableDebugLogOutput";
    /// The don't-check-game-completeness parameter key.
    private static final String KEY_NOT_CHECK_GAME = "notCheckGame";

    @Override
    public String getName() {
        return "set_launch_behavior";
    }

    @Override
    public String getDescription() {
        return "Sets the launcher launch-behavior settings of a Minecraft instance (what the LAUNCHER does around "
                + "a launch — NOT in-game video/options). Every parameter is optional; only the ones you pass are "
                + "changed, and each is correctly overridden for this instance. Parameters: "
                + "launcherVisibility (one of: close, hide, keep, hide_and_reopen — what the HMCL window does after "
                + "the game starts), "
                + "processPriority (one of: low, below_normal, normal, above_normal, high — OS scheduling priority "
                + "of the game process), "
                + "allowAutoAgent (boolean — let HMCL attach Java agents to improve the game), "
                + "disableAutoGameOptions (boolean — do NOT auto-generate options.txt/game options), "
                + "showLogs (boolean — open the log window after launch), "
                + "enableDebugLogOutput (boolean — verbose debug logging), "
                + "notCheckGame (boolean — skip the game completeness check before launch), "
                + "instance (optional, the instance id; defaults to the currently selected instance). "
                + "WRITES the instance's HMCL game-settings file. "
                + "If you pass none of the behavior parameters it only REPORTS the current effective values and "
                + "whether each is overridden or inherited from the parent preset.";
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

        // Generic aliases are NOT accepted: this tool has several distinct named parameters, so the
        // instance resolver must not steal a value from the generic 'query' fallback.
        InstanceToolSupport.ResolvedInstance target =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (target.failure() != null) {
            return target.failure();
        }
        final String instance = target.name();

        // Read the current effective values (and the override flags) up front — used both for the
        // read-only report and for the "before -> after" summary of a write.
        GameSettings.Effective effective;
        try {
            effective = repository.getEffectiveGameSettings(instance);
        } catch (Throwable e) {
            return ToolResult.failure("Failed to read the launch-behavior settings of '" + instance + "': "
                    + e.getMessage());
        }

        LauncherVisibility curLauncherVisibility = effective.getInheritable(GameSettings::launcherVisibilityProperty);
        ProcessPriority curProcessPriority = effective.getInheritable(GameSettings::processPriorityProperty);
        boolean curAllowAutoAgent = effective.getInheritable(GameSettings::allowAutoAgentProperty);
        boolean curDisableAutoGameOptions = effective.getInheritable(GameSettings::disableAutoGameOptionsProperty);
        boolean curShowLogs = effective.getInheritable(GameSettings::showLogsProperty);
        boolean curEnableDebugLogOutput = effective.getInheritable(GameSettings::enableDebugLogOutputProperty);
        boolean curNotCheckGame = effective.getInheritable(GameSettings::notCheckGameProperty);

        @Nullable GameSettings.Instance existing = effective.getInstance();
        boolean ovLauncherVisibility = isOverridden(existing, GameSettings.PROPERTY_LAUNCHER_VISIBILITY);
        boolean ovProcessPriority = isOverridden(existing, GameSettings.PROPERTY_PROCESS_PRIORITY);
        boolean ovAllowAutoAgent = isOverridden(existing, GameSettings.PROPERTY_ALLOW_AUTO_AGENT);
        boolean ovDisableAutoGameOptions = isOverridden(existing, GameSettings.PROPERTY_DISABLE_AUTO_GAME_OPTIONS);
        boolean ovShowLogs = isOverridden(existing, GameSettings.PROPERTY_SHOW_LOGS);
        boolean ovEnableDebugLogOutput = isOverridden(existing, GameSettings.PROPERTY_ENABLE_DEBUG_LOG_OUTPUT);
        boolean ovNotCheckGame = isOverridden(existing, GameSettings.PROPERTY_NOT_CHECK_GAME);

        // Parse & validate every supplied parameter BEFORE mutating anything, so a bad value in one
        // field never leaves a half-applied change behind.
        List<Consumer<GameSettings.Instance>> changes = new ArrayList<>();
        List<String> summary = new ArrayList<>();

        if (hasValue(parameters, KEY_LAUNCHER_VISIBILITY)) {
            String raw = String.valueOf(parameters.get(KEY_LAUNCHER_VISIBILITY));
            LauncherVisibility parsed = parseEnum(LauncherVisibility.values(), raw);
            if (parsed == null) {
                return enumFailure(KEY_LAUNCHER_VISIBILITY, raw, LauncherVisibility.values());
            }
            LauncherVisibility value = parsed;
            changes.add(setting -> {
                setting.launcherVisibilityProperty().setValue(value);
                setting.getOverrideProperties().add(GameSettings.PROPERTY_LAUNCHER_VISIBILITY);
            });
            summary.add(changeLine(KEY_LAUNCHER_VISIBILITY, enumText(curLauncherVisibility), ovLauncherVisibility,
                    enumText(value)));
        }

        if (hasValue(parameters, KEY_PROCESS_PRIORITY)) {
            String raw = String.valueOf(parameters.get(KEY_PROCESS_PRIORITY));
            ProcessPriority parsed = parseEnum(ProcessPriority.values(), raw);
            if (parsed == null) {
                return enumFailure(KEY_PROCESS_PRIORITY, raw, ProcessPriority.values());
            }
            ProcessPriority value = parsed;
            changes.add(setting -> {
                setting.processPriorityProperty().setValue(value);
                setting.getOverrideProperties().add(GameSettings.PROPERTY_PROCESS_PRIORITY);
            });
            summary.add(changeLine(KEY_PROCESS_PRIORITY, enumText(curProcessPriority), ovProcessPriority,
                    enumText(value)));
        }

        ToolResult error;
        if ((error = stageBoolean(parameters, KEY_ALLOW_AUTO_AGENT, GameSettings.PROPERTY_ALLOW_AUTO_AGENT,
                GameSettings::allowAutoAgentProperty, curAllowAutoAgent, ovAllowAutoAgent, changes, summary)) != null) {
            return error;
        }
        if ((error = stageBoolean(parameters, KEY_DISABLE_AUTO_GAME_OPTIONS,
                GameSettings.PROPERTY_DISABLE_AUTO_GAME_OPTIONS, GameSettings::disableAutoGameOptionsProperty,
                curDisableAutoGameOptions, ovDisableAutoGameOptions, changes, summary)) != null) {
            return error;
        }
        if ((error = stageBoolean(parameters, KEY_SHOW_LOGS, GameSettings.PROPERTY_SHOW_LOGS,
                GameSettings::showLogsProperty, curShowLogs, ovShowLogs, changes, summary)) != null) {
            return error;
        }
        if ((error = stageBoolean(parameters, KEY_ENABLE_DEBUG_LOG_OUTPUT,
                GameSettings.PROPERTY_ENABLE_DEBUG_LOG_OUTPUT, GameSettings::enableDebugLogOutputProperty,
                curEnableDebugLogOutput, ovEnableDebugLogOutput, changes, summary)) != null) {
            return error;
        }
        if ((error = stageBoolean(parameters, KEY_NOT_CHECK_GAME, GameSettings.PROPERTY_NOT_CHECK_GAME,
                GameSettings::notCheckGameProperty, curNotCheckGame, ovNotCheckGame, changes, summary)) != null) {
            return error;
        }

        // Nothing to change: degrade to a read-only report of the current effective values.
        if (changes.isEmpty()) {
            StringBuilder report = new StringBuilder("Launch-behavior settings of instance '" + instance
                    + "' (effective value, and whether this instance overrides it or inherits it from its "
                    + "parent preset):\n");
            report.append(stateLine(KEY_LAUNCHER_VISIBILITY, enumText(curLauncherVisibility), ovLauncherVisibility));
            report.append(stateLine(KEY_PROCESS_PRIORITY, enumText(curProcessPriority), ovProcessPriority));
            report.append(stateLine(KEY_ALLOW_AUTO_AGENT, boolText(curAllowAutoAgent), ovAllowAutoAgent));
            report.append(stateLine(KEY_DISABLE_AUTO_GAME_OPTIONS, boolText(curDisableAutoGameOptions),
                    ovDisableAutoGameOptions));
            report.append(stateLine(KEY_SHOW_LOGS, boolText(curShowLogs), ovShowLogs));
            report.append(stateLine(KEY_ENABLE_DEBUG_LOG_OUTPUT, boolText(curEnableDebugLogOutput),
                    ovEnableDebugLogOutput));
            report.append(stateLine(KEY_NOT_CHECK_GAME, boolText(curNotCheckGame), ovNotCheckGame));
            report.append("To change any of these, call this tool again with the matching parameter "
                    + "(launcherVisibility / processPriority / allowAutoAgent / disableAutoGameOptions / "
                    + "showLogs / enableDebugLogOutput / notCheckGame).");
            return ToolResult.success(report.toString());
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
                // Each applier both setValue()s and registers the property in overrideProperties, so
                // the value actually takes effect at launch time instead of being persisted but
                // silently ignored while the instance keeps inheriting the parent preset's value.
                for (Consumer<GameSettings.Instance> change : changes) {
                    change.accept(setting);
                }
                repository.saveGameSettings(instance); // explicit save in addition to the auto-save listener
                StringBuilder ok = new StringBuilder("Updated launch behavior of instance '" + instance + "':\n");
                for (String line : summary) {
                    ok.append("  ").append(line).append('\n');
                }
                ok.append("Each changed setting is now overridden for this instance. "
                        + "The change takes effect the next time the instance is launched.");
                result.set(ToolResult.success(ok.toString()));
            } catch (Throwable e) {
                result.set(ToolResult.failure("Failed to set launch behavior for '" + instance + "': "
                        + e.getMessage()));
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
                    return ToolResult.failure("Timed out while applying the launch-behavior settings; "
                            + "the UI did not respond.");
                }
            } catch (IllegalStateException e) {
                // JavaFX runtime not started (should not happen inside the running launcher UI).
                return ToolResult.failure("Cannot apply the launch-behavior settings: "
                        + "the JavaFX runtime is unavailable.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.failure("Interrupted while applying the launch-behavior settings.");
            }
        }

        ToolResult finalResult = result.get();
        return finalResult != null ? finalResult
                : ToolResult.failure("Unknown error while setting launch behavior for '" + instance + "'.");
    }

    /// Parses, validates, and stages a boolean launch-behavior parameter. Returns a failure
    /// [ToolResult] when the supplied value is not a boolean, {@code null} otherwise (including when
    /// the parameter is absent, in which case nothing is staged).
    private static @Nullable ToolResult stageBoolean(
            Map<String, Object> parameters,
            String key,
            String propertyName,
            Function<GameSettings, ? extends SettingProperty<Boolean>> propertyGetter,
            boolean currentEffective,
            boolean currentlyOverridden,
            List<Consumer<GameSettings.Instance>> changes,
            List<String> summary) {
        if (!hasValue(parameters, key)) {
            return null;
        }
        Boolean parsed = parseBoolean(parameters.get(key));
        if (parsed == null) {
            return ToolFailures.failure(
                    "Parameter '" + key + "' must be a boolean (true or false), got: " + parameters.get(key),
                    ToolFailures.Retryable.YES,
                    "this is an on/off setting; only true and false are accepted",
                    "retry with " + key + "=true or " + key + "=false");
        }
        boolean value = parsed;
        changes.add(setting -> {
            propertyGetter.apply(setting).setValue(value);
            setting.getOverrideProperties().add(propertyName);
        });
        summary.add(changeLine(key, boolText(currentEffective), currentlyOverridden, boolText(value)));
        return null;
    }

    /// Whether an override is currently registered for {@code propertyName} on the given (nullable)
    /// instance setting. A {@code null} instance has no overrides — every value is inherited.
    private static boolean isOverridden(@Nullable GameSettings.Instance instance, String propertyName) {
        return instance != null && instance.getOverrideProperties().contains(propertyName);
    }

    /// Whether a parameter is present with a usable (non-null, non-blank) value.
    private static boolean hasValue(Map<String, Object> parameters, String key) {
        if (!parameters.containsKey(key)) {
            return false;
        }
        Object value = parameters.get(key);
        if (value == null) {
            return false;
        }
        if (value instanceof String s) {
            return !s.trim().isEmpty();
        }
        return true;
    }

    /// Parses a boolean parameter value. Accepts a {@link Boolean} directly and the case-insensitive
    /// strings {@code "true"}/{@code "false"}; returns {@code null} for anything else so the caller
    /// can reject an invalid value rather than silently coercing it.
    private static @Nullable Boolean parseBoolean(@Nullable Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value != null) {
            String text = value.toString().trim().toLowerCase(Locale.ROOT);
            if (text.equals("true")) {
                return Boolean.TRUE;
            }
            if (text.equals("false")) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    /// Matches {@code raw} case-insensitively against an enum's constant names, tolerating hyphens in
    /// place of underscores (so {@code "hide-and-reopen"} resolves to {@code HIDE_AND_REOPEN}).
    /// Returns {@code null} when no constant matches.
    private static <E extends Enum<E>> @Nullable E parseEnum(E[] values, String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (E value : values) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }
        return null;
    }

    /// The unified "invalid enum value" failure envelope, listing the accepted values.
    private static <E extends Enum<E>> ToolResult enumFailure(String key, String raw, E[] values) {
        return ToolFailures.failure(
                "Parameter '" + key + "' has an invalid value '" + raw + "'; valid values are: " + enumOptions(values),
                ToolFailures.Retryable.YES,
                "the value must be exactly one of the accepted options",
                "retry with " + key + "=<one of " + enumOptions(values) + ">");
    }

    /// Comma-joined lowercase enum constant names, e.g. {@code "close, hide, keep, hide_and_reopen"}.
    private static <E extends Enum<E>> String enumOptions(E[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(enumText(values[i]));
        }
        return sb.toString();
    }

    /// The lowercase textual form of an enum constant, matching the parameter vocabulary.
    private static String enumText(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static String boolText(boolean value) {
        return value ? "true" : "false";
    }

    /// A read-report line: {@code "  <key> = <value> (overridden by this instance)\n"} or
    /// {@code "(inherited from preset)"}.
    private static String stateLine(String key, String value, boolean overridden) {
        return "  " + key + " = " + value + " ("
                + (overridden ? "overridden by this instance" : "inherited from preset") + ")\n";
    }

    /// A write-summary line: {@code "<key>: <before> (<inherited|overridden>) -> <after>"}.
    private static String changeLine(String key, String before, boolean wasOverridden, String after) {
        return key + ": " + before + " (" + (wasOverridden ? "was overridden" : "was inherited")
                + ") -> " + after;
    }
}
