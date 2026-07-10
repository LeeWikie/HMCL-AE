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

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks in the SearchModsTool "loader not verified per result" disclaimer (T14 / prompt-contracts
/// #4) — the flip side of the InstanceTool de-over-promise: search must openly state it does NOT
/// filter results by loader so the model does not assume search + mods_install double-check loader.
public final class SearchModsToolDescriptionTest {

    private final SearchModsTool tool = new SearchModsTool();

    @Test
    void descriptionStatesResultsAreNotVerifiedAgainstLoader() {
        String description = tool.getDescription();
        assertTrue(description.contains("NOT individually verified against 'loader'"),
                "description must disclaim per-result loader verification: " + description);
        assertTrue(description.contains("only hints the query"),
                "description must state loader only hints the query: " + description);
        assertTrue(description.contains("mods_install"),
                "description must point to mods_install as the real hard-filter: " + description);
    }
}
