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

import org.jetbrains.annotations.Nullable;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;

/// Resolves the addon source AI content tools should default to when the model does not pass
/// an explicit `source` parameter.
///
/// Previously `search_mods` / `install_modpack` hard-coded Modrinth as the default, silently
/// ignoring the user's "default addon source" launcher preference
/// (`settings().defaultAddonSourceProperty()`, the same preference the native download UI
/// honors in `HMCLLocalizedDownloadListPage`). This helper reads that preference and applies
/// the same no-API-key fallback the tools already use for explicit CurseForge requests: a
/// CurseForge preference quietly degrades to Modrinth when this build carries no CurseForge
/// API key, because a default must never turn into a hard failure the user did not ask for.
///
/// An explicit `source` parameter from the model always wins over this default (and keeps its
/// existing "CurseForge not configured" failure semantics).
final class DefaultAddonSource {

    private DefaultAddonSource() {
    }

    /// The preferred default source as a [ContentToolSupport.Source], for tools that work
    /// with the enum (e.g. `install_modpack`).
    static ContentToolSupport.Source preferred() {
        return "curseforge".equals(preferredName())
                ? ContentToolSupport.Source.CURSEFORGE
                : ContentToolSupport.Source.MODRINTH;
    }

    /// The preferred default source as its canonical lowercase name (`"modrinth"` /
    /// `"curseforge"`), for tools that work with plain strings (e.g. `search_mods`).
    static String preferredName() {
        return normalize(preferenceValue(), ContentToolSupport.isCurseForgeAvailable());
    }

    /// Pure decision core (unit-testable without launcher bootstrap): maps the raw persisted
    /// preference plus CurseForge availability to the effective default source name.
    /// Anything that is not exactly a usable "curseforge" preference resolves to `"modrinth"`,
    /// which needs no API key and therefore always works.
    static String normalize(@Nullable String preference, boolean curseForgeAvailable) {
        if (preference != null && "curseforge".equalsIgnoreCase(preference.trim()) && curseForgeAvailable) {
            return "curseforge";
        }
        return "modrinth";
    }

    /// Reads the raw persisted preference; returns `null` when the launcher settings are not
    /// initialized (e.g. bare unit tests), which [#normalize] treats as Modrinth.
    @Nullable
    private static String preferenceValue() {
        try {
            return settings().defaultAddonSourceProperty().get();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
