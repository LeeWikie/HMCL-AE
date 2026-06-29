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
package org.jackhuang.hmcl.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for the 10 extended global options added to {@link AiSettings}
/// (agent behaviour / safety / UI): save→load round-trip and defaults when
/// the fields are absent from an older config file.
public final class AiGlobalOptionsSettingsTest {

    @TempDir
    Path tempDir;

    /// All 10 extended global options should round-trip through save/load.
    @Test
    public void testExtendedGlobalOptionsRoundTrip() throws IOException {
        AiSettings settings = new AiSettings(tempDir);
        settings.maxToolCyclesProperty().set(7);
        settings.maxContextMessagesProperty().set(12);
        settings.toolResultMaxCharsProperty().set(4096);
        settings.requestTimeoutSecondsProperty().set(45);
        settings.toolCallLoggingEnabledProperty().set(false);   // default true
        settings.shellToolEnabledProperty().set(false);         // default true
        settings.webAccessEnabledProperty().set(false);         // default true
        settings.fileWriteConfirmEnabledProperty().set(true);   // default false
        settings.autoScrollEnabledProperty().set(false);        // default true
        settings.sendOnEnterProperty().set(false);              // default true
        settings.save();

        AiSettings loaded = new AiSettings(tempDir);
        loaded.load();

        assertEquals(7, loaded.getMaxToolCycles());
        assertEquals(12, loaded.getMaxContextMessages());
        assertEquals(4096, loaded.getToolResultMaxChars());
        assertEquals(45, loaded.getRequestTimeoutSeconds());
        assertFalse(loaded.isToolCallLoggingEnabled());
        assertFalse(loaded.isShellToolEnabled());
        assertFalse(loaded.isWebAccessEnabled());
        assertTrue(loaded.isFileWriteConfirmEnabled());
        assertFalse(loaded.isAutoScrollEnabled());
        assertFalse(loaded.isSendOnEnter());
    }

    /// When the settings file does not exist, all extended options use their defaults.
    @Test
    public void testExtendedGlobalOptionsDefaultsWhenFileMissing() throws IOException {
        AiSettings settings = new AiSettings(tempDir);
        settings.load(); // no file

        assertEquals(AiSettings.DEFAULT_MAX_TOOL_CYCLES, settings.getMaxToolCycles());
        assertEquals(AiSettings.DEFAULT_MAX_CONTEXT_MESSAGES, settings.getMaxContextMessages());
        assertEquals(AiSettings.DEFAULT_TOOL_RESULT_MAX_CHARS, settings.getToolResultMaxChars());
        assertEquals(AiSettings.DEFAULT_REQUEST_TIMEOUT_SECONDS, settings.getRequestTimeoutSeconds());
        assertEquals(AiSettings.DEFAULT_TOOL_CALL_LOGGING_ENABLED, settings.isToolCallLoggingEnabled());
        assertEquals(AiSettings.DEFAULT_SHELL_TOOL_ENABLED, settings.isShellToolEnabled());
        assertEquals(AiSettings.DEFAULT_WEB_ACCESS_ENABLED, settings.isWebAccessEnabled());
        assertEquals(AiSettings.DEFAULT_FILE_WRITE_CONFIRM_ENABLED, settings.isFileWriteConfirmEnabled());
        assertEquals(AiSettings.DEFAULT_AUTO_SCROLL_ENABLED, settings.isAutoScrollEnabled());
        assertEquals(AiSettings.DEFAULT_SEND_ON_ENTER, settings.isSendOnEnter());
    }

    /// An older config file that predates these options (fields absent) must
    /// load with the documented defaults rather than zero/false.
    @Test
    public void testLegacyConfigWithoutExtendedFieldsUsesDefaults() throws IOException {
        // A minimal legacy file containing only the classic single-provider fields.
        String legacyJson = "{\n"
                + "  \"endpoint\": \"https://api.openai.com/v1/chat/completions\",\n"
                + "  \"model\": \"gpt-4o-mini\",\n"
                + "  \"maxTokens\": 4096,\n"
                + "  \"temperature\": 0.7\n"
                + "}";
        Files.writeString(tempDir.resolve(AiSettings.FILE_NAME), legacyJson, StandardCharsets.UTF_8);

        AiSettings loaded = new AiSettings(tempDir);
        loaded.load();

        // Absent numeric/boolean fields fall back to PersistedData initializer defaults.
        assertEquals(AiSettings.DEFAULT_MAX_TOOL_CYCLES, loaded.getMaxToolCycles());
        assertEquals(AiSettings.DEFAULT_MAX_CONTEXT_MESSAGES, loaded.getMaxContextMessages());
        assertEquals(AiSettings.DEFAULT_TOOL_RESULT_MAX_CHARS, loaded.getToolResultMaxChars());
        assertEquals(AiSettings.DEFAULT_REQUEST_TIMEOUT_SECONDS, loaded.getRequestTimeoutSeconds());
        assertEquals(AiSettings.DEFAULT_TOOL_CALL_LOGGING_ENABLED, loaded.isToolCallLoggingEnabled());
        assertEquals(AiSettings.DEFAULT_SHELL_TOOL_ENABLED, loaded.isShellToolEnabled());
        assertEquals(AiSettings.DEFAULT_WEB_ACCESS_ENABLED, loaded.isWebAccessEnabled());
        assertEquals(AiSettings.DEFAULT_FILE_WRITE_CONFIRM_ENABLED, loaded.isFileWriteConfirmEnabled());
        assertEquals(AiSettings.DEFAULT_AUTO_SCROLL_ENABLED, loaded.isAutoScrollEnabled());
        assertEquals(AiSettings.DEFAULT_SEND_ON_ENTER, loaded.isSendOnEnter());
    }
}
