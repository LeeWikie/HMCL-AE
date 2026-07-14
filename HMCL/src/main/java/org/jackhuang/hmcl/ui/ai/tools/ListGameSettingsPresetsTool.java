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
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.setting.GameSettingsPresetID;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jackhuang.hmcl.util.i18n.LocalizedText;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// A read-only tool that lists every GLOBAL game-settings preset defined in HMCL
/// (`config/game-settings.json`), so the model can "see" the presets before it references one —
/// e.g. before setting an instance's parent preset, or when asked about the launcher's global
/// version-isolation policy.
///
/// This reuses HMCL's own preset store directly, exactly like the native settings UI
/// ([`PresetManagementPane`]):
/// - [`SettingsManager#getGameSettings()`] for the full preset list,
/// - the same display-name resolution [`PresetManagementPane#getPresetDisplayName`] uses
///   (custom localized name → automatic `Preset N` number → raw preset id), reproduced here
///   because that helper is package-private to `ui.game`,
/// - [`GameSettings.Preset#defaultIsolationTypeProperty()`] for each preset's
///   `defaultIsolationType` (`ALWAYS` / `MODDED` / `NEVER` — the strategy that decides whether a
///   newly-created instance under that preset is isolated),
/// - [`LauncherSettings#defaultGameSettingsPresetProperty()`] to mark which preset is the global
///   default (read WITHOUT the side-effecting
///   [`SettingsManager#getDefaultGameSettingsPresetOrCreate()`], which would mutate the store —
///   inappropriate for a read-only tool).
///
/// Each preset is reported with its id (a stable `game-settings-preset:<uuid>` [`GameSettingsPresetID`]),
/// display name, and default isolation type.
///
/// Permission level: READ_ONLY. It never modifies any launcher state. The preset list is bound to
/// the settings UI and is only mutated on the JavaFX thread, so the snapshot is taken on that
/// thread to avoid a concurrent-modification race; when the JavaFX runtime is unavailable (e.g.
/// headless tests) it degrades to a direct read, which is safe because it is purely read-only.
@NotNullByDefault
public final class ListGameSettingsPresetsTool implements Tool {

    @Override
    public String getName() {
        return "list_game_settings_presets";
    }

    @Override
    public String getDescription() {
        return "Lists every GLOBAL game-settings preset defined in HMCL. Takes no parameters. Returns each "
                + "preset's id (a 'game-settings-preset:<uuid>' string), display name, and defaultIsolationType "
                + "(ALWAYS = new instances under this preset are always isolated into their own version folder, "
                + "MODDED = only instances with a mod loader are isolated, NEVER = new instances are never isolated), "
                + "and marks which preset is the global default. Read-only. Use this before referencing a preset id "
                + "(for example when setting an instance's parent preset) or when asked about the launcher's global "
                + "version-isolation policy. These are GLOBAL presets, not per-instance settings.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        AtomicReference<ToolResult> result = new AtomicReference<>();
        Runnable task = () -> result.set(buildReport());

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
                    return ToolResult.failure("Timed out while reading the game-settings presets; the UI did not respond.");
                }
            } catch (IllegalStateException e) {
                // JavaFX runtime not started (e.g. headless tests). Since this is a pure read, fall
                // back to a direct read on the current thread instead of failing outright.
                task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.failure("Interrupted while reading the game-settings presets.");
            }
        }

        ToolResult finalResult = result.get();
        return finalResult != null ? finalResult
                : ToolResult.failure("Unknown error while reading the game-settings presets.");
    }

    /// Builds the preset report. Must run where the preset list is safe to iterate (the JavaFX
    /// thread when the runtime is up — see [#execute]).
    private static ToolResult buildReport() {
        List<GameSettings.Preset> presets;
        @Nullable GameSettingsPresetID defaultId;
        try {
            // Defensive snapshot: the backing list is UI-bound and mutated on the FX thread.
            presets = new ArrayList<>(SettingsManager.getGameSettings());
            defaultId = SettingsManager.settings().defaultGameSettingsPresetProperty().get();
        } catch (Throwable e) {
            return ToolResult.failure("The game settings are not loaded yet; please try again in a moment. ("
                    + e.getMessage() + ")");
        }

        if (presets.isEmpty()) {
            return ToolResult.success("No global game-settings presets are defined yet. HMCL creates a default "
                    + "preset automatically the first time one is needed (for example when the settings page is "
                    + "opened or an instance is created).");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Global game-settings presets (").append(presets.size()).append("):\n");
        for (GameSettings.Preset preset : presets) {
            GameSettingsPresetID id = preset.idProperty().getValue();
            boolean isDefault = defaultId != null && defaultId.equals(id);
            sb.append(isDefault ? "  * " : "  - ").append(presetDisplayName(preset));
            if (isDefault) {
                sb.append(" (default)");
            }
            sb.append('\n');
            sb.append("      id                  : ").append(id).append('\n');
            sb.append("      defaultIsolationType: ").append(isolationTypeName(preset)).append('\n');
        }
        sb.append("Notes: the default preset is used for newly created instances and as the fallback for any "
                + "setting an instance does not override. defaultIsolationType (ALWAYS/MODDED/NEVER) only affects "
                + "how NEW instances created under a preset choose their isolation; it does not retroactively change "
                + "existing instances.");
        return ToolResult.success(sb.toString().trim());
    }

    /// Resolves a preset's `defaultIsolationType` name, mirroring HMCL's own
    /// [`GameSettings#getDirectValue`] fallback: the property is normally the constructor default
    /// (`MODDED`), but a deserialized `null` is coerced back to that default rather than NPEing.
    private static String isolationTypeName(GameSettings.Preset preset) {
        var property = preset.defaultIsolationTypeProperty();
        var value = property.getValue();
        if (value == null) {
            value = property.defaultValue();
        }
        return value != null ? value.name() : "MODDED";
    }

    /// Resolves a preset's user-visible display name, reproducing
    /// [`PresetManagementPane#getPresetDisplayName`] (package-private to `ui.game`): a custom
    /// localized name if set, otherwise the automatic `Preset N` label, otherwise the raw id.
    private static String presetDisplayName(GameSettings.Preset preset) {
        @Nullable LocalizedText customName = preset.nameProperty().getValue();
        @Nullable String name = customName != null
                ? customName.getText(I18n.getLocale().getCandidateLocales())
                : null;
        if (StringUtils.isNotBlank(name)) {
            return name;
        }

        @Nullable Integer autoNameNumber = preset.autoNameNumberProperty().getValue();
        if (autoNameNumber == null) {
            return preset.idProperty().getValue().toString();
        }
        return i18n("settings.type.global.preset.auto_name", autoNameNumber);
    }
}
