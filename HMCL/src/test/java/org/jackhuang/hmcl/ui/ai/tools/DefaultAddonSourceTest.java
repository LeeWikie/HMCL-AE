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

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// L6: `search_mods` / `install_modpack` used to hard-code Modrinth as the default source,
/// ignoring the user's "default addon source" launcher preference. These tests pin the pure
/// decision core [DefaultAddonSource#normalize] (preference × CurseForge availability) and
/// the string↔enum agreement of the two public accessors.
public final class DefaultAddonSourceTest {

    @Test
    public void curseforgePreferenceIsHonoredWhenAvailable() {
        assertEquals("curseforge", DefaultAddonSource.normalize("curseforge", true));
        assertEquals("curseforge", DefaultAddonSource.normalize("CurseForge", true), "case-insensitive");
        assertEquals("curseforge", DefaultAddonSource.normalize("  curseforge  ", true), "trimmed");
    }

    @Test
    public void curseforgePreferenceFallsBackToModrinthWithoutApiKey() {
        // The same no-key fallback the tools apply for explicit CurseForge requests: a mere
        // default preference must degrade silently instead of failing the tool call.
        assertEquals("modrinth", DefaultAddonSource.normalize("curseforge", false));
    }

    @Test
    public void modrinthAndUnknownPreferencesResolveToModrinth() {
        assertEquals("modrinth", DefaultAddonSource.normalize("modrinth", true));
        assertEquals("modrinth", DefaultAddonSource.normalize(null, true));
        assertEquals("modrinth", DefaultAddonSource.normalize("", true));
        assertEquals("modrinth", DefaultAddonSource.normalize("something-else", true));
    }

    @Test
    public void accessorsAgreeAndNeverThrowWithoutLauncherBootstrap() {
        // In a bare unit-test JVM the launcher settings may not be initialized; the default
        // source must still resolve (to a valid source) instead of throwing.
        String name = assertDoesNotThrow(DefaultAddonSource::preferredName);
        assertTrue(Set.of("modrinth", "curseforge").contains(name));

        ContentToolSupport.Source source = assertDoesNotThrow(DefaultAddonSource::preferred);
        assertEquals(name, source == ContentToolSupport.Source.CURSEFORGE ? "curseforge" : "modrinth",
                "enum and string accessors must agree");
    }
}
