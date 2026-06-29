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

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for {@link DangerousCommands#isDangerous(String)}.
public final class DangerousCommandsTest {

    @Test
    public void testDestructiveCommandsAreFlagged() {
        String[] dangerous = {
                "rm -rf /",
                "rm -rf ~/Documents",
                "rm -r somedir",
                "rm -f file",
                "sudo rm -rf --no-preserve-root /",
                "rmdir /s C:\\temp",
                "del /s /q C:\\stuff",
                "Remove-Item C:\\data -Recurse -Force",
                "Remove-Item HKLM:\\SOFTWARE\\Foo",
                "format C:",
                "mkfs.ext4 /dev/sda1",
                "dd if=/dev/zero of=/dev/sda",
                "diskpart",
                "reg delete HKLM\\Software\\Foo",
                "shutdown -h now",
                "reboot",
                "poweroff",
                "killall -9 java",
                "kill -9 1234",
                "chmod -R 000 /",
                "echo x > /dev/sda",
                ":(){ :|:& };:",
        };
        for (String cmd : dangerous) {
            assertTrue(DangerousCommands.isDangerous(cmd),
                    "Expected dangerous: " + cmd);
        }
    }

    @Test
    public void testCaseInsensitiveDetection() {
        assertTrue(DangerousCommands.isDangerous("RM -RF /"));
        assertTrue(DangerousCommands.isDangerous("SHUTDOWN now"));
        assertTrue(DangerousCommands.isDangerous("FORMAT C:"));
    }

    @Test
    public void testBenignCommandsAreNotFlagged() {
        String[] safe = {
                "ls -la",
                "echo hello world",
                "git status",
                "java -version",
                "cat README.md",
                "cd /home/user",
                "mkdir new-folder",
                "gradle build",
                "rm",                       // bare rm without destructive flags
                "remove the file please",   // natural language, not a command
                "format the output nicely", // 'format' without drive letter
        };
        for (String cmd : safe) {
            assertFalse(DangerousCommands.isDangerous(cmd),
                    "Expected benign: " + cmd);
        }
    }

    @Test
    public void testNullAndBlankAreSafe() {
        assertFalse(DangerousCommands.isDangerous(null));
        assertFalse(DangerousCommands.isDangerous(""));
        assertFalse(DangerousCommands.isDangerous("   "));
    }
}
