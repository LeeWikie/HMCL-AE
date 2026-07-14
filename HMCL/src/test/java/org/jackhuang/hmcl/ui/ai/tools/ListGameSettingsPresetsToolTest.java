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

import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.setting.DefaultIsolationType;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.setting.GameSettingsPresetID;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.util.i18n.LocalizedText;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [ListGameSettingsPresetsTool]'s deterministic contract and its two real report branches
/// through [SettingsManager]'s actual preset store ([ProfileFixture]): the metadata/description
/// contract, the "no presets defined yet" empty state, and a populated store (one preset,
/// registered as the global default) reported with its id/display-name/isolation-type/default
/// marker.
///
/// This tool takes NO parameters ([ListGameSettingsPresetsTool#execute] never reads its
/// `parameters` map at all), so unlike most other AI tools in this package there is no
/// missing/invalid-parameter guard branch to cover before touching [SettingsManager] — every
/// branch this tool can take requires reading the global preset store, so both cases below go
/// through [ProfileFixture] rather than one running fixture-free.
///
/// [ProfileFixture] already starts the JavaFX toolkit
/// ([org.jackhuang.hmcl.JavaFXLauncher#start]) before `Profiles.init()` runs, so
/// [ListGameSettingsPresetsTool#execute] takes its normal `Platform.runLater` + latch path here
/// (rather than the headless direct-read fallback), exactly like the real launcher.
///
/// NOT covered here: the "custom name unset, `Preset N` auto-numbered" display-name fallback
/// (mirrors [org.jackhuang.hmcl.ui.game.PresetManagementPane#getPresetDisplayName]'s
/// `i18n("settings.type.global.preset.auto_name", n)` branch) and the "deserialized-null
/// `defaultIsolationType` coerced back to its `MODDED` default" fallback — both are deliberately
/// left untouched by test setup (a preset built here always sets an explicit custom name and an
/// explicit isolation type) rather than exercised, so they are left to the manual test checklist.
public final class ListGameSettingsPresetsToolTest {

    private final ListGameSettingsPresetsTool tool = new ListGameSettingsPresetsTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("list_game_settings_presets", tool.getName());
        String description = tool.getDescription();
        assertTrue(description.contains("Takes no parameters"),
                "the description must advertise that this tool takes no parameters: " + description);
        assertTrue(description.contains("defaultIsolationType"),
                "the description must document the defaultIsolationType field: " + description);
        assertTrue(description.contains("ALWAYS") && description.contains("MODDED") && description.contains("NEVER"),
                "the description must explain all three isolation-type values: " + description);
        assertTrue(description.contains("game-settings-preset:<uuid>"),
                "the description must document the id format contract: " + description);
        assertTrue(description.contains("Read-only"),
                "the description must state the permission level: " + description);
    }

    @Test
    void reportsEmptyStateWhenNoGlobalPresetsAreDefined() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            // ProfileFixture installs a brand-new, empty GameSettingsPresets store, so nothing
            // needs to be created here: this is the fixture's baseline state.
            ToolResult result = tool.execute(Map.of());

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("No global game-settings presets are defined yet"),
                    "unexpected empty-state message: " + result.getOutput());
        }
    }

    @Test
    void listsARegisteredPresetWithItsIdNameIsolationTypeAndDefaultMarker() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            GameSettingsPresetID id = GameSettingsPresetID.generate();
            GameSettings.Preset preset = new GameSettings.Preset(id);
            preset.nameProperty().setValue(LocalizedText.plain("My Preset"));
            preset.defaultIsolationTypeProperty().setValue(DefaultIsolationType.ALWAYS);
            SettingsManager.getGameSettings().add(preset);
            SettingsManager.settings().defaultGameSettingsPresetProperty().set(id);

            ToolResult result = tool.execute(Map.of());

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            String output = result.getOutput();
            assertTrue(output.contains("(1)"), "expected a preset count of 1: " + output);
            assertTrue(output.contains("My Preset"), "expected the custom display name: " + output);
            assertTrue(output.contains(id.toString()), "expected the preset's stable id: " + output);
            assertTrue(output.contains("ALWAYS"), "expected the configured isolation type: " + output);
            assertTrue(output.contains("(default)"),
                    "expected the preset marked as the global default: " + output);
        }
    }
}
