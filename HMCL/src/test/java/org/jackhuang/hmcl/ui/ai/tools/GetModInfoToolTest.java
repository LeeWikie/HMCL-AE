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

import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [GetModInfoTool]'s substring-match resolution over the native
/// [org.jackhuang.hmcl.addon.mod.ModManager]/[org.jackhuang.hmcl.addon.mod.LocalModFile] parse
/// (empty instance / zero-match / exactly one / several), using a real [ProfileFixture]-backed
/// instance on disk. The zero-match branch now carries the actual installed file names (B10/#19),
/// asserted as a well-formed failure envelope.
public final class GetModInfoToolTest {

    private final GetModInfoTool tool = new GetModInfoTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("get_mod_info", tool.getName());
        assertTrue(tool.getDescription().toLowerCase().contains("mod"));
    }

    @Test
    void missingModParameterFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            ToolResult result = tool.execute(Map.of("instance", "Existing"));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("mod"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void emptyInstanceReportsNoMods() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);

            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "anything"));
            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("no mods installed"),
                    "unexpected message: " + result.getOutput());
        }
    }

    @Test
    void zeroMatchFailureListsInstalledFileNamesWithEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            Files.writeString(modsDir.resolve("JEI.jar"), "a");
            Files.writeString(modsDir.resolve("Sodium.jar"), "b");

            // A query that matches nothing must carry the real installed file names, mirroring the
            // multi-match branch, and be a well-formed retryable envelope.
            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "NoSuchMod"));
            assertFalse(result.isSuccess());
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(result.getError().contains("Retryable: yes"), "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("installed mods:"),
                    "should carry the installed file list: " + result.getError());
            // LocalModFile.getFileName() is extension-less (FileUtils.getNameWithoutExtension).
            assertTrue(result.getError().contains("JEI"), "should list JEI: " + result.getError());
            assertTrue(result.getError().contains("Sodium"), "should list Sodium: " + result.getError());
            assertTrue(result.getError().contains("list_mods"), "should point at list_mods: " + result.getError());
        }
    }

    @Test
    void singleMatchReturnsInfo() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            Files.writeString(modsDir.resolve("Sodium.jar"), "jar-bytes");

            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "sodium"));
            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("Sodium"), "unexpected message: " + result.getOutput());
            assertTrue(result.getOutput().contains("Mod info for instance"),
                    "unexpected message: " + result.getOutput());
        }
    }

    @Test
    void ambiguousMatchReturnsCandidateList() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            Files.writeString(modsDir.resolve("JEI-forge.jar"), "a");
            Files.writeString(modsDir.resolve("JEI-fabric.jar"), "b");

            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "jei"));
            assertTrue(result.isSuccess(), "expected a candidate list: " + result.getError());
            // LocalModFile.getFileName() is extension-less (FileUtils.getNameWithoutExtension).
            assertTrue(result.getOutput().contains("JEI-forge"), "unexpected message: " + result.getOutput());
            assertTrue(result.getOutput().contains("JEI-fabric"), "unexpected message: " + result.getOutput());
        }
    }
}
