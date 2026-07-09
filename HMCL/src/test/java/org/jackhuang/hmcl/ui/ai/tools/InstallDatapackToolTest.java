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

/// Covers [InstallDatapackTool]'s parameter-resolution/validation branches (missing world/source,
/// source not found, source not a .zip, target world missing, name-collision refusal) and the
/// success path (copies the source zip into `saves/<world>/datapacks/`), using a real
/// [ProfileFixture]-backed instance and a real temp source file on disk — no mocks, mirroring
/// [DeleteInstanceTool Test][DeleteInstanceToolTest] (this tool's only external dependencies are
/// local file I/O plus [org.jackhuang.hmcl.setting.Profiles] / [org.jackhuang.hmcl.game.HMCLGameRepository]).
public final class InstallDatapackToolTest {

    private final InstallDatapackTool tool = new InstallDatapackTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("install_datapack", tool.getName());
        assertTrue(tool.getDescription().toLowerCase().contains("datapack"));
    }

    @Test
    void missingWorldParameterFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            ToolResult result = tool.execute(Map.of("instance", "Existing", "source", "/tmp/whatever.zip"));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("world"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void missingSourceParameterFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "MyWorld"));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("source"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void sourceNotFoundFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path missing = fx.baseDir().resolve("does-not-exist.zip");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "MyWorld",
                    "source", missing.toString()));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("was not found"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void sourceNotAZipFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path notZip = fx.baseDir().resolve("datapack.txt");
            Files.writeString(notZip, "not a zip");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "MyWorld",
                    "source", notZip.toString()));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("must be a .zip file"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void targetWorldMissingFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path zip = fx.baseDir().resolve("mydatapack.zip");
            Files.writeString(zip, "fake-zip-bytes");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "NoSuchWorld",
                    "source", zip.toString()));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("was not found"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void installsTheDatapackIntoTheWorldsDatapacksFolder() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld");
            Files.createDirectories(worldDir);
            Path zip = fx.baseDir().resolve("mydatapack.zip");
            Files.writeString(zip, "fake-zip-bytes");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "MyWorld",
                    "source", zip.toString()));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            Path destination = worldDir.resolve("datapacks").resolve("mydatapack.zip");
            assertTrue(Files.isRegularFile(destination));
            assertEquals("fake-zip-bytes", Files.readString(destination));
        }
    }

    @Test
    void refusesToOverwriteAnExistingDatapackWithTheSameName() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld");
            Path datapacksDir = worldDir.resolve("datapacks");
            Files.createDirectories(datapacksDir);
            Files.writeString(datapacksDir.resolve("mydatapack.zip"), "already-here");
            Path zip = fx.baseDir().resolve("mydatapack.zip");
            Files.writeString(zip, "new-content");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "MyWorld",
                    "source", zip.toString()));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("already exists"), "unexpected message: " + result.getError());
            assertEquals("already-here", Files.readString(datapacksDir.resolve("mydatapack.zip")),
                    "the existing datapack must be untouched");
        }
    }
}
