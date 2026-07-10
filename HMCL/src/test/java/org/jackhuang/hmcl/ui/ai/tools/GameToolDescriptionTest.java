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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks in the `game(action="launch")` mismatch caution (T16 / prompt-contracts #8): a
/// loader/game-version/mod mismatch never surfaces in chat — it only manifests as an in-game crash
/// or immediate exit the model cannot observe. The description must warn about this and forbid
/// claiming success beyond "the process started".
public final class GameToolDescriptionTest {

    private final GameTool tool = new GameTool(() -> 10);

    @Test
    void launchDescriptionWarnsMismatchIsInvisibleToChat() {
        String description = tool.getDescription();
        assertTrue(description.contains("CAUTION"),
                "launch description must carry the mismatch caution: " + description);
        assertTrue(description.contains("NOT reported back to this chat"),
                "launch description must state the mismatch is not reported to chat: " + description);
        assertTrue(description.contains("in-game crash"),
                "launch description must name the in-game crash symptom: " + description);
    }

    @Test
    void launchDescriptionForbidsOverclaimingSuccess() {
        String description = tool.getDescription();
        assertTrue(description.contains("the process started"),
                "launch description must cap success claims at 'the process started': " + description);
        assertTrue(description.contains("mods_check_updates") || description.contains("latest.log"),
                "launch description must offer a verification path (mods_check_updates / latest.log): " + description);
    }
}
