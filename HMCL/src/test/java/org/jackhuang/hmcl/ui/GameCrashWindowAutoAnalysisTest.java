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
package org.jackhuang.hmcl.ui;

import org.jackhuang.hmcl.ai.AiSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// L5: the "auto crash analysis" switch (stored in `ai-settings.json`, shown in the AI
/// settings page) used to have no consumer at all. `GameCrashWindow.isAutoCrashAnalysisEnabled`
/// is the decision gate `LauncherHelper` → `autoDiagnoseIfEnabled()` now consults; these tests
/// pin its read-from-disk semantics without needing a JavaFX Stage.
public final class GameCrashWindowAutoAnalysisTest {

    private static void writeSettings(Path dir, String json) throws IOException {
        Files.writeString(dir.resolve(AiSettings.FILE_NAME), json);
    }

    @Test
    public void readsPersistedSwitchFromDisk(@TempDir Path dir) throws IOException {
        writeSettings(dir, "{\"autoCrashAnalysisEnabled\": false}");
        assertFalse(GameCrashWindow.isAutoCrashAnalysisEnabled(dir),
                "a persisted false must disable auto crash analysis");

        writeSettings(dir, "{\"autoCrashAnalysisEnabled\": true}");
        assertTrue(GameCrashWindow.isAutoCrashAnalysisEnabled(dir),
                "a persisted true must enable auto crash analysis");
    }

    @Test
    public void missingSettingsFileFollowsTheAiSettingsDefault(@TempDir Path dir) {
        // No ai-settings.json at all: AiSettings.load() keeps its defaults, and the switch
        // defaults to enabled (same default the settings page shows).
        assertTrue(GameCrashWindow.isAutoCrashAnalysisEnabled(dir));
    }

    @Test
    public void corruptSettingsFileDisablesAutoAnalysisInsteadOfThrowing(@TempDir Path dir) throws IOException {
        writeSettings(dir, "{ this is not json");
        assertFalse(GameCrashWindow.isAutoCrashAnalysisEnabled(dir),
                "a broken settings file must not auto-open the AI page (and must not throw)");
    }
}
