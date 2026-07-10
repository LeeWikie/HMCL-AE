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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks in the `mods_install` description/schema de-over-promise (T14 / prompt-contracts #3): the
/// text used to claim the model should pass "the loader/gameVersion that 'search' already resolved",
/// but `search` never verifies loader/version per result. The rewrite must drop that promise, tell
/// the model to pass what the USER wants, and teach it what to do when the install then reports an
/// unsupported loader. Complements {@link InstanceToolModsInstallParamsTest}, which still guards
/// that the three params remain documented for mods_install.
public final class InstanceToolModsDescriptionTest {

    private final InstanceTool tool = new InstanceTool(() -> false, () -> 10, () -> false);

    @Test
    void descriptionNoLongerOverPromisesSearchResolution() {
        String description = tool.getDescription();
        assertFalse(description.contains("already resolved"),
                "description must not claim search 'already resolved' the loader/version: " + description);
        assertTrue(description.contains("does NOT"),
                "description must state search does NOT verify per-result loader/version: " + description);
        assertTrue(description.contains("USER actually wants"),
                "description must tell the model to pass what the user wants: " + description);
    }

    @Test
    void descriptionTeachesRecoveryFromUnsupportedLoaderError() {
        String description = tool.getDescription();
        assertTrue(description.contains("no version supports loader"),
                "description must reference the unsupported-loader failure: " + description);
        assertTrue(description.contains("search for an alternative")
                        || description.contains("pick one of those"),
                "description must tell the model to pick a listed loader or search for an alternative: " + description);
    }

    @Test
    void schemaLoaderAndGameVersionDropTheResolvedPromiseButKeepModsInstall() {
        String schema = tool.getInputSchemaJson();
        assertFalse(schema.contains("already resolved"),
                "schema property descriptions must not claim search 'already resolved' anything: " + schema);
        assertTrue(propertyDescriptionFor(schema, "loader").contains("mods_install"),
                "'loader' must still document mods_install");
        assertTrue(propertyDescriptionFor(schema, "gameVersion").contains("mods_install"),
                "'gameVersion' must still document mods_install");
        assertTrue(propertyDescriptionFor(schema, "loader").contains("does NOT verify"),
                "'loader' description must say search does NOT verify per result");
    }

    private static String propertyDescriptionFor(String schemaJson, String property) {
        String marker = "\"" + property + "\": {";
        int start = schemaJson.indexOf(marker);
        assertTrue(start >= 0, "property '" + property + "' not found in schema");
        int end = schemaJson.indexOf('\n', start);
        return schemaJson.substring(start, end >= 0 ? end : schemaJson.length());
    }
}
