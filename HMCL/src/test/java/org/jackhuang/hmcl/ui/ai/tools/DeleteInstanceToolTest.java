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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Covers [DeleteInstanceTool]'s parameter-resolution/validation branches and both the
/// confirm-gate and the two delete strategies (recycle-bin vs. permanent), using a real
/// [ProfileFixture]-backed instance on disk rather than mocks (this tool's only external
/// dependencies are [org.jackhuang.hmcl.setting.Profiles] / [org.jackhuang.hmcl.game.HMCLGameRepository]
/// and [FileTrash], both driven by real file-system state here).
public final class DeleteInstanceToolTest {

    @Test
    void missingInstanceParameterFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            DeleteInstanceTool tool = new DeleteInstanceTool(() -> false);

            ToolResult result = tool.execute(Map.of("confirm", true));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("instance"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void nonexistentInstanceFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            DeleteInstanceTool tool = new DeleteInstanceTool(() -> false);

            ToolResult result = tool.execute(Map.of("instance", "DoesNotExist", "confirm", true));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("No such instance"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void notConfirmedFailsAndDeletesNothing() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("KeepMe");
            Path versionDir = fx.repository().getVersionRoot("KeepMe");
            DeleteInstanceTool tool = new DeleteInstanceTool(() -> false);

            ToolResult result = tool.execute(Map.of("instance", "KeepMe"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Not confirmed"), "unexpected message: " + result.getError());
            assertTrue(Files.isDirectory(versionDir), "instance directory must be untouched when not confirmed");
            assertTrue(fx.repository().hasVersion("KeepMe"));
        }
    }

    @Test
    void confirmedDeletePermanentlyRemovesInstanceFromDiskAndRepository() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("ToDelete");
            Path versionDir = fx.repository().getVersionRoot("ToDelete");
            assertTrue(Files.isDirectory(versionDir));
            // toRecycleBin=false forces the permanent/native delete path regardless of platform
            // trash support, so this test is deterministic everywhere.
            DeleteInstanceTool tool = new DeleteInstanceTool(() -> false);

            ToolResult result = tool.execute(Map.of("instance", "ToDelete", "confirm", true));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertFalse(Files.exists(versionDir), "version directory should be gone from disk");
            assertFalse(fx.repository().hasVersion("ToDelete"), "repository should have forgotten the version");
        }
    }

    @Test
    void confirmAcceptsStringTrueCaseInsensitively() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("ToDelete2");
            Path versionDir = fx.repository().getVersionRoot("ToDelete2");
            DeleteInstanceTool tool = new DeleteInstanceTool(() -> false);

            ToolResult result = tool.execute(Map.of("instance", "ToDelete2", "confirm", "TRUE"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertFalse(Files.exists(versionDir));
        }
    }

    @Test
    void confirmedDeleteToRecycleBinMovesInstanceWhenTrashSupported() throws Exception {
        assumeTrue(FileTrash.trashSupported(), "no recycle-bin support on this platform/environment");
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("ToTrash");
            Path versionDir = fx.repository().getVersionRoot("ToTrash");
            DeleteInstanceTool tool = new DeleteInstanceTool(() -> true);

            ToolResult result = tool.execute(Map.of("instance", "ToTrash", "confirm", true));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertNotNull(result.getOutput());
            assertFalse(Files.exists(versionDir), "version directory should be gone from its original location");
            assertFalse(fx.repository().hasVersion("ToTrash"));
        }
    }

    @Test
    void toolMetadataIsSensible() {
        DeleteInstanceTool tool = new DeleteInstanceTool(() -> false);
        assertEquals("delete_instance", tool.getName());
        assertTrue(tool.getDescription().toLowerCase().contains("delete"));
    }
}
