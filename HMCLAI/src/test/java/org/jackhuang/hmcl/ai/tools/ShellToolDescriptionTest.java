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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks in the ShellTool description contract (T13 / prompt-contracts #1): the shell "golden rule"
/// (last resort, don't shadow dedicated tools, don't hand-toggle mod suffixes) must appear in the
/// description itself — visible at every call site — and the Windows branch must carry the
/// PowerShell 5.1 platform pitfalls (`&&`/`||`, `New-Item -Force`, `2>&1`). Tested through the
/// static builder so both OS branches are covered regardless of the host the test runs on.
public final class ShellToolDescriptionTest {

    @Test
    void goldenRuleIsPresentOnBothPlatforms() {
        for (String desc : new String[]{
                ShellTool.buildDescription("Windows 11 (10.0)", "PowerShell", true, 60),
                ShellTool.buildDescription("Linux (6.1)", "bash", false, 60)}) {
            assertTrue(desc.contains("LAST RESORT"),
                    () -> "shell description must state it is a last resort: " + desc);
            assertTrue(desc.contains("find/grep/cat"),
                    () -> "shell description must steer away from find/grep/cat: " + desc);
            assertTrue(desc.contains("mods_toggle"),
                    () -> "shell description must forbid hand-toggling mod suffixes via shell: " + desc);
            // Encoding-obfuscation ban and the mandatory 'description' param are unchanged contract.
            assertTrue(desc.contains("-EncodedCommand"), () -> "must still ban -EncodedCommand: " + desc);
            assertTrue(desc.contains("'description'"), () -> "must still require the description param: " + desc);
            assertTrue(desc.contains("60s timeout"), () -> "timeout value must be interpolated: " + desc);
        }
    }

    @Test
    void windowsBranchCarriesPowerShell51Pitfalls() {
        String desc = ShellTool.buildDescription("Windows 11 (10.0)", "PowerShell", true, 60);
        assertTrue(desc.contains("PowerShell 5.1"), () -> "must name the PowerShell 5.1 pitfall block: " + desc);
        assertTrue(desc.contains("'&&'"), () -> "must warn that && does not exist: " + desc);
        assertTrue(desc.contains("New-Item -Force"), () -> "must warn New-Item -Force truncates: " + desc);
        assertTrue(desc.contains("2>&1"), () -> "must warn about the 2>&1 NativeCommandError trap: " + desc);
    }

    @Test
    void unixBranchOmitsPowerShellPitfalls() {
        String desc = ShellTool.buildDescription("Linux (6.1)", "bash", false, 60);
        assertFalse(desc.contains("PowerShell 5.1"),
                () -> "Unix shell description must not carry PowerShell 5.1 pitfalls: " + desc);
        assertFalse(desc.contains("New-Item"),
                () -> "Unix shell description must not mention PowerShell cmdlets: " + desc);
        assertTrue(desc.contains("bash on Linux/macOS"),
                () -> "Unix description should echo the detected shell name: " + desc);
    }
}
