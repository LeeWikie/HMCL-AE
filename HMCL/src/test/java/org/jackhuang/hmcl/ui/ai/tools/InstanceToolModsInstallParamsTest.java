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

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks in the `mods_install` facade-schema fix: before this fix, `InstanceTool`'s schema and
/// description advertised `loader`/`gameVersion` only for `create`/`download_java`, and had no
/// `version` parameter at all — so a model calling `mods_install` had no field to carry the
/// loader/game-version/exact-build info `search` had already resolved, and every install silently
/// degraded to `ContentToolSupport#resolveVersion`'s "newest published across every loader and MC
/// version" fallback (see {@link ContentToolSupportResolveVersionTest} for that fallback's actual
/// behavior). These assertions guard against the schema/description regressing back to omitting
/// those three parameters for `mods_install`, which would silently reopen the bug even though
/// `resolveVersion`/`InstallModTool` themselves handle the parameters correctly once they arrive.
public final class InstanceToolModsInstallParamsTest {

    private final InstanceTool tool = new InstanceTool(() -> false, () -> 10, () -> false);

    @Test
    void schemaAdvertisesLoaderGameVersionAndVersionForModsInstall() {
        String schema = tool.getInputSchemaJson();

        assertTrue(schema.contains("\"version\""), "schema must declare a 'version' property at all");
        assertTrue(schema.contains("\"loader\""), "schema must declare a 'loader' property at all");
        assertTrue(schema.contains("\"gameVersion\""), "schema must declare a 'gameVersion' property at all");

        // Each of the three must be *documented for mods_install specifically*, not just present
        // for create/download_java — that's exactly the gap the bug report identified.
        assertTrue(propertyDescriptionFor(schema, "loader").contains("mods_install"),
                "'loader' property must mention mods_install in its description");
        assertTrue(propertyDescriptionFor(schema, "gameVersion").contains("mods_install"),
                "'gameVersion' property must mention mods_install in its description");
        assertTrue(propertyDescriptionFor(schema, "version").contains("mods_install"),
                "'version' property must mention mods_install in its description");
    }

    @Test
    void topLevelDescriptionDocumentsModsInstallWithLoaderGameVersionAndVersion() {
        String description = tool.getDescription();
        int modsInstallIndex = description.indexOf("mods_install (");
        assertTrue(modsInstallIndex >= 0, "description must document mods_install's own parameter list");

        // Pull just the mods_install parenthetical so this doesn't pass by accident because
        // "loader"/"gameVersion"/"version" appear somewhere else in the (long) tool description.
        int closingParen = description.indexOf(')', modsInstallIndex);
        String modsInstallParams = description.substring(modsInstallIndex, closingParen + 1);

        assertTrue(modsInstallParams.contains("loader"), "mods_install params must list loader: " + modsInstallParams);
        assertTrue(modsInstallParams.contains("gameVersion"), "mods_install params must list gameVersion: " + modsInstallParams);
        assertTrue(modsInstallParams.contains("version"), "mods_install params must list version: " + modsInstallParams);
    }

    @Test
    void modsInstallFailsClosedWithoutHittingTheNetworkWhenNoInstanceIsResolvedYet() {
        // refreshRunDir() is never called in this test, so the mods_install action must fail with a
        // clear "select an instance first" message rather than NPE-ing or silently no-op'ing —
        // exercised here purely as a smoke test that the action still dispatches correctly now that
        // its parameter set has grown.
        Map<String, Object> params = new HashMap<>();
        params.put("action", "mods_install");
        params.put("id", "some-mod");
        params.put("loader", "neoforge");
        params.put("gameVersion", "1.21.1");
        params.put("version", "1.2.3");

        var result = tool.execute(params);

        assertTrue(!result.isSuccess());
        assertTrue(result.getError().contains("instance"), "unexpected message: " + result.getError());
    }

    private static String propertyDescriptionFor(String schemaJson, String property) {
        String marker = "\"" + property + "\": {";
        int start = schemaJson.indexOf(marker);
        assertTrue(start >= 0, "property '" + property + "' not found in schema");
        int end = schemaJson.indexOf('\n', start);
        return schemaJson.substring(start, end >= 0 ? end : schemaJson.length());
    }
}
