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
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [ExportModpackTool]'s parameter-resolution/validation branches: the unsupported-format
/// hard failure, instance resolution (none selected / named instance missing), and the
/// output-path collision guard — all of which return BEFORE the tool would ever build/run the real
/// [org.jackhuang.hmcl.modpack.modrinth.ModrinthModpackExportTask] pipeline. That pipeline itself
/// (parsing the instance's real Minecraft/loader version via `LibraryAnalyzer`, walking mods/
/// resourcepacks/shaders, zipping) is intentionally NOT exercised here — it needs a realistic,
/// fully-formed version.json fixture well beyond this tool's own parameter-handling logic, which is
/// what these tests target (mirrors [DeleteInstanceToolTest]'s no-mocks, real-filesystem approach).
public final class ExportModpackToolTest {

    private final ExportModpackTool tool = new ExportModpackTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("export_modpack", tool.getName());
        assertTrue(tool.getDescription().contains(".mrpack"));
    }

    @Test
    void unsupportedFormatFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "format", "mcbbs"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Unsupported format"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void noInstanceSelectedFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            // No createInstance() call: nothing is selected and the profile has no versions.
            ToolResult result = tool.execute(Map.of());

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("No instance selected"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void namedNonexistentInstanceFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");

            ToolResult result = tool.execute(Map.of("instance", "DoesNotExist"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("No such instance"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void refusesToOverwriteAnExistingDefaultOutputFile() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path defaultOutput = fx.repository().getBaseDirectory().resolve("Existing.mrpack");
            Files.createDirectories(defaultOutput.getParent());
            Files.writeString(defaultOutput, "already-here");

            ToolResult result = tool.execute(Map.of("instance", "Existing"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Output file already exists"), "unexpected message: " + result.getError());
            assertEquals("already-here", Files.readString(defaultOutput), "the existing file must be untouched");
        }
    }

    @Test
    void refusesToOverwriteAnExplicitTargetFile() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path explicitOutput = fx.baseDir().resolve("custom-export.mrpack");
            Files.writeString(explicitOutput, "already-here");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "target", explicitOutput.toString()));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Output file already exists"), "unexpected message: " + result.getError());
        }
    }
}
