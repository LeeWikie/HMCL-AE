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
package org.jackhuang.hmcl.ai.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.jackhuang.hmcl.ai.tools.ToolFailureAssertions.assertFailureEnvelope;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [GameContextTool]'s snapshot-provenance annotation (ST-2: the cache may disagree with a
/// same-turn live `list_instances`, so the output must tell the model it is a turn-start snapshot)
/// and the unified failure-envelope format on the no-directory path.
public final class GameContextToolTest {

    private final GameContextTool tool = new GameContextTool();

    @Test
    void successOutputAnnotatesTheTurnStartSnapshot(@TempDir Path gameDir) {
        tool.setGameDir(gameDir);
        tool.setInstanceInfo("1.20.1", true);

        ToolResult result = tool.execute(Map.of());

        assertTrue(result.isSuccess(), () -> "expected success: " + result.getError());
        String out = result.getOutput();
        assertTrue(out.contains("snapshot"), () -> "output must flag its snapshot provenance: " + out);
        assertTrue(out.contains("list_instances"),
                () -> "output must point the model at the live source for current state: " + out);
        // The actual data is still there.
        assertTrue(out.contains("gameDirectory"), () -> "unexpected output: " + out);
        assertTrue(out.contains("modsDir"), () -> "unexpected output: " + out);
    }

    @Test
    void descriptionWarnsAboutMidTurnInstanceSwitch() {
        String desc = tool.getDescription();
        assertTrue(desc.contains("snapshot"), () -> "description must state the values are a snapshot: " + desc);
        assertTrue(desc.contains("list_instances"), () -> "description must name the live fallback: " + desc);
    }

    @Test
    void missingGameDirectoryFailsWithWellFormedEnvelope() {
        // No setGameDir(...) call: gameDir is null.
        ToolResult result = tool.execute(Map.of());
        assertFailureEnvelope(result);
    }
}
