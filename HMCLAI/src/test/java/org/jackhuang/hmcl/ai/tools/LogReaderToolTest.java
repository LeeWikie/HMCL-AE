/*
 * Hello Minecraft! Launcher
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
package org.jackhuang.hmcl.ai.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for [`LogReaderTool`] using temporary directories and files.
///
/// No real Minecraft installation is required — all log files are created
/// in temporary directories via [`@TempDir`].
public final class LogReaderToolTest {

    /// Verifies that the tool reads the tail of `latest.log` from the
    /// configured game directory and returns it successfully.
    @Test
    public void testReadMinecraftLog(@TempDir Path gameDir) throws IOException {
        Path logsDir = Files.createDirectories(gameDir.resolve("logs"));
        Path latestLog = logsDir.resolve("latest.log");
        String logContent = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\n";
        Files.writeString(latestLog, logContent, StandardCharsets.UTF_8);

        LogReaderTool tool = new LogReaderTool(gameDir, gameDir);
        ToolResult result = tool.execute(Map.of("source", "minecraft", "lines", 3));

        assertTrue(result.isSuccess(), "should succeed when latest.log exists");
        assertTrue(result.getOutput().contains("Minecraft log"),
                "output should indicate Minecraft log section");
        assertTrue(result.getOutput().contains("Line 3"),
                "output should contain lines from the tail");
        assertTrue(result.getOutput().contains("Line 5"),
                "output should contain lines from the tail");
        assertFalse(result.getOutput().contains("Line 1"),
                "output should NOT contain lines before the tail window");
    }

    /// Verifies that the tool reads the most recent HMCL timestamped log file.
    @Test
    public void testReadHmclLog(@TempDir Path hmclLogDir) throws IOException {
        Path olderLog = hmclLogDir.resolve("2026-06-25T12-00-00.log");
        Path newerLog = hmclLogDir.resolve("2026-06-26T15-30-00.log");
        Files.writeString(olderLog, "old content\n", StandardCharsets.UTF_8);
        Files.writeString(newerLog, "newer content\n", StandardCharsets.UTF_8);

        LogReaderTool tool = new LogReaderTool(hmclLogDir, hmclLogDir);
        ToolResult result = tool.execute(Map.of("source", "hmcl"));

        assertTrue(result.isSuccess(), "should succeed when HMCL logs exist");
        assertTrue(result.getOutput().contains("HMCL log"),
                "output should indicate HMCL log section");
        assertTrue(result.getOutput().contains("2026-06-26T15-30-00.log"),
                "output should reference the most recent log file name");
        assertTrue(result.getOutput().contains("newer content"),
                "output should contain content from the most recent log");
        assertFalse(result.getOutput().contains("2026-06-25"),
                "output should NOT contain content from the older log");
    }

    /// When neither log source exists, the tool returns a success result
    /// with informational messages indicating no logs were found.
    @Test
    public void testMissingDirectoriesReportsNotFound(@TempDir Path emptyDir) {
        LogReaderTool tool = new LogReaderTool(emptyDir, emptyDir);
        ToolResult result = tool.execute(Map.of());

        assertTrue(result.isSuccess(),
                "should succeed with informational output when no logs exist");
        assertTrue(result.getOutput().contains("not found"),
                "output should indicate that no log files were found");
    }

    /// When `source` is set to `"both"` (the default), both log sources
    /// are attempted even when only one exists.
    @Test
    public void testBothSourcesWithOnlyMinecraftAvailable(@TempDir Path gameDir) throws IOException {
        Path logsDir = Files.createDirectories(gameDir.resolve("logs"));
        Path latestLog = logsDir.resolve("latest.log");
        Files.writeString(latestLog, "mc content\n", StandardCharsets.UTF_8);

        LogReaderTool tool = new LogReaderTool(gameDir, gameDir);
        ToolResult result = tool.execute(Map.of("source", "both", "lines", 10));

        assertTrue(result.isSuccess(), "should succeed when at least one source exists");
        assertTrue(result.getOutput().contains("Minecraft log"),
                "output should contain Minecraft log section");
    }

    /// Verifies the tool name and description are correct.
    @Test
    public void testNameAndDescription(@TempDir Path dir) {
        LogReaderTool tool = new LogReaderTool(dir, dir);
        assertEquals("read_minecraft_log", tool.getName(),
                "tool name should match");
        assertFalse(tool.getDescription().isBlank(),
                "description should not be blank");
    }
}
