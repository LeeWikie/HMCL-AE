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

import org.jackhuang.hmcl.addon.repository.CurseForgeRemoteAddonRepository;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Covers only the parameter-validation branches of [InstallShaderTool] (via its shared base
/// [AbstractFileInstallTool]) that are reachable WITHOUT a live network call: the missing-'id'
/// hard failure, and the CurseForge-source failure when no API key is configured for this build
/// (see [CurseForgeRemoteAddonRepository#isAvailable]) — both return before
/// [ContentToolSupport#resolveVersion] would perform any remote lookup. The actual
/// resolve/download path (`ContentToolSupport.resolveVersion` /
/// `ContentToolSupport.runDownloadWithFallback`) requires reaching Modrinth/CurseForge over the
/// network and is intentionally NOT exercised here.
public final class InstallShaderToolTest {

    private final InstallShaderTool tool = new InstallShaderTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("install_shader", tool.getName());
        assertTrue(tool.getDescription().contains("shaderpacks"));
        assertTrue(tool.getDescription().contains("modrinth"));
    }

    @Test
    void missingIdParameterFailsWithoutAnyNetworkCall() {
        ToolResult result = tool.execute(new HashMap<>());
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("id"), "unexpected message: " + result.getError());
    }

    @Test
    void curseForgeSourceFailsWhenNotConfiguredForThisBuild() {
        // This test build has no CurseForge API key baked in (no -Dhmcl.curseforge.apikey and no
        // jar manifest attribute), so this branch is deterministic without any network access.
        assumeFalse(CurseForgeRemoteAddonRepository.isAvailable(),
                "this build has a CurseForge API key configured; the 'not configured' branch cannot be exercised");

        Map<String, Object> params = new HashMap<>();
        params.put("id", "complementary-reimagined");
        params.put("source", "curseforge");

        ToolResult result = tool.execute(params);

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("CurseForge is not configured"), "unexpected message: " + result.getError());
    }
}
