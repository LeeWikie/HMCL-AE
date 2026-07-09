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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [CreateWorldBackupTool]'s parameter-resolution/validation branches and its delegation
/// to [WorldBackupManager#createBackup] (versioned snapshot creation + retention pruning), using a
/// real [ProfileFixture]-backed instance and world folder on disk — mirroring
/// [DeleteInstanceToolTest]'s no-mocks approach (this tool's only external dependencies are
/// [org.jackhuang.hmcl.setting.Profiles] / [org.jackhuang.hmcl.game.HMCLGameRepository] and
/// [WorldBackupManager], both driven by real file-system state here).
public final class CreateWorldBackupToolTest {

    @Test
    void reportsCorrectMetadata() {
        CreateWorldBackupTool tool = new CreateWorldBackupTool(() -> 5);
        assertEquals("create_world_backup", tool.getName());
        assertTrue(tool.getDescription().toLowerCase().contains("backup"));
    }

    @Test
    void missingWorldParameterFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            CreateWorldBackupTool tool = new CreateWorldBackupTool(() -> 5);

            ToolResult result = tool.execute(Map.of("instance", "Existing"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("world"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void nonexistentInstanceFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            CreateWorldBackupTool tool = new CreateWorldBackupTool(() -> 5);

            ToolResult result = tool.execute(Map.of("instance", "DoesNotExist", "world", "MyWorld"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("does not exist"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void nonexistentWorldFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            CreateWorldBackupTool tool = new CreateWorldBackupTool(() -> 5);

            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "NoSuchWorld"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Failed to back up"), "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("was not found"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void createsATimestampedSnapshotOfTheWorld() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld");
            Files.createDirectories(worldDir);
            Files.writeString(worldDir.resolve("level.dat"), "fake-level-data");
            CreateWorldBackupTool tool = new CreateWorldBackupTool(() -> 5);

            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "MyWorld"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("Snapshot id"), "unexpected message: " + result.getOutput());

            List<WorldBackupManager.BackupInfo> backups = WorldBackupManager.listBackups("Existing", "MyWorld");
            assertEquals(1, backups.size());
            assertEquals(1, backups.get(0).fileCount());
        }
    }

    @Test
    void prunesOldSnapshotsBeyondRetention() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld");
            Files.createDirectories(worldDir);
            Files.writeString(worldDir.resolve("level.dat"), "fake-level-data");
            CreateWorldBackupTool tool = new CreateWorldBackupTool(() -> 2);

            // Three snapshots at retention=2 must leave exactly 2 behind. Sleep briefly between
            // calls so each gets a distinct second-granularity timestamp id.
            tool.execute(Map.of("instance", "Existing", "world", "MyWorld"));
            TimeUnit.SECONDS.sleep(1);
            tool.execute(Map.of("instance", "Existing", "world", "MyWorld"));
            TimeUnit.SECONDS.sleep(1);
            ToolResult third = tool.execute(Map.of("instance", "Existing", "world", "MyWorld"));

            assertTrue(third.isSuccess(), "expected success: " + third.getError());
            assertTrue(third.getOutput().contains("pruned"), "unexpected message: " + third.getOutput());

            List<WorldBackupManager.BackupInfo> backups = WorldBackupManager.listBackups("Existing", "MyWorld");
            assertEquals(2, backups.size(), "retention=2 must leave exactly 2 snapshots");
        }
    }
}
