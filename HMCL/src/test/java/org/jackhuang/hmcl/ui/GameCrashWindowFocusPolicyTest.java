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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins `GameCrashWindow.shouldBringMainWindowToFront`, the decision gate that used to be
/// missing: `submitToAiAssistant` unconditionally called `Controllers.getStage().show()` /
/// `.toFront()` / `Controllers.navigate(ai)` regardless of whether it was invoked from the
/// user clicking the "AI diagnose" button (`interactive == true`) or from the automatic
/// "auto crash analysis" background path (`interactive == false`) — so every auto-detected
/// crash yanked the launcher main window to the front and stole OS focus, even if the user
/// was doing something else entirely at the time.
///
/// `submitToAiAssistant` itself still can't be exercised here: `Controllers`/`Stage` are
/// static and require a live JavaFX environment (see the `@Disabled` `GameCrashWindowTest`
/// for the shape such a test would take). The interactive/auto branch was therefore pulled
/// out into this standalone pure function so the actual decision — not just the AI-settings
/// switch feeding into it (see `GameCrashWindowAutoAnalysisTest`) — has a real regression test.
///
/// Manual verification recommended on a real build (JavaFX Stage/Controllers can't be faked
/// in a unit test): (1) enable "auto crash analysis" in AI settings, launch a game version
/// configured to crash immediately, and confirm the HMCL main window is NOT brought to front /
/// focused when the crash window appears — only the small native crash-report window (which
/// must still show) — while the AI page still receives the prompt as a queued neutral event
/// pill once you switch to it yourself; (2) with the main window focused elsewhere (or
/// minimized), click the crash window's "AI diagnose" button and confirm the main window IS
/// raised, brought to front, and navigated to the AI page, and the crash window closes.
public final class GameCrashWindowFocusPolicyTest {

    @Test
    public void interactiveClickBringsMainWindowToFront() {
        assertTrue(GameCrashWindow.shouldBringMainWindowToFront(true),
                "the user clicking the \"AI diagnose\" button must still jump to the AI page");
    }

    @Test
    public void automaticAnalysisNeverStealsFocus() {
        assertFalse(GameCrashWindow.shouldBringMainWindowToFront(false),
                "auto crash analysis must never show/toFront/navigate the main window");
    }
}
