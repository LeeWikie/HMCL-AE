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

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks in the dangerous-command detection, especially the base64 / -EncodedCommand decoding that
/// closes the encoding-bypass hole, and guards against false positives on benign input.
public final class DangerousCommandsTest {

    private static String b64Utf8(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64Utf16(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_16LE));
    }

    @Test
    void plainDangerousCommandsAreFlagged() {
        assertTrue(DangerousCommands.isDangerous("rm -rf /var/data"));
        assertTrue(DangerousCommands.isDangerous("format C:"));
        assertTrue(DangerousCommands.isDangerous("dd if=/dev/zero of=/dev/sda"));
        assertTrue(DangerousCommands.isDangerous("Remove-Item C:/foo -Recurse"));
    }

    @Test
    void rmLongOptionsAreFlagged() {
        // GNU long options were previously missed by the short-flag-only pattern.
        assertTrue(DangerousCommands.isDangerous("rm --recursive --force /home/user/data"));
        assertTrue(DangerousCommands.isDangerous("rm --force --recursive /"));
        assertTrue(DangerousCommands.isDangerous("rm --recursive dir"));
        // a single-file rm with no recursive/force flag is not flagged
        assertFalse(DangerousCommands.isDangerous("rm notes.txt"));
    }

    /// Windows-native delete spellings the model naturally emits on a Windows shell (cmd built-ins
    /// and PowerShell aliases of Remove-Item) — previously all missed.
    @Test
    void windowsDeleteSpellingsAreFlagged() {
        assertTrue(DangerousCommands.isDangerous("rd /s /q D:\\data"));
        assertTrue(DangerousCommands.isDangerous("cmd /c rd /s /q C:\\Users\\me\\Desktop\\stuff"));
        assertTrue(DangerousCommands.isDangerous("rd /q /s D:\\data"));           // switch order swapped
        assertTrue(DangerousCommands.isDangerous("rmdir /s C:\\temp"));
        assertTrue(DangerousCommands.isDangerous("del /s C:\\backup2"));           // switches before path
        assertTrue(DangerousCommands.isDangerous("del /q C:\\x"));
        assertTrue(DangerousCommands.isDangerous("del C:\\x /s"));                 // switches after path
        assertTrue(DangerousCommands.isDangerous("erase /s C:\\x"));
        assertTrue(DangerousCommands.isDangerous("ri -Recurse -Force C:\\Users\\me\\Documents"));
        assertTrue(DangerousCommands.isDangerous("rd -Recurse -Force $env:USERPROFILE\\Documents"));
        assertTrue(DangerousCommands.isDangerous("del -Recurse -Force C:\\x"));
        assertTrue(DangerousCommands.isDangerous("Remove-Item -rec -fo C:\\x"));   // prefix abbreviation
        assertTrue(DangerousCommands.isDangerous("Remove-Item -re C:\\x"));
        // But plain single-file deletes stay unflagged.
        assertFalse(DangerousCommands.isDangerous("del C:\\temp\\a.txt"));
        assertFalse(DangerousCommands.isDangerous("Remove-Item C:\\temp\\a.txt"));
        assertFalse(DangerousCommands.isDangerous("ri C:\\temp\\a.txt"));
    }

    /// powershell.exe accepts ANY unambiguous prefix of -EncodedCommand; the fail-closed flag
    /// detection must cover the middle spellings, not just -enc..-encoded and the full name.
    @Test
    void encodedCommandPrefixAbbreviationsAreRecognized() {
        String encoded = b64Utf16("Remove-Item C:/Users/me/world -Recurse -Force");
        assertTrue(DangerousCommands.isDangerous("powershell -EncodedCo " + encoded));
        assertTrue(DangerousCommands.isDangerous("powershell -EncodedCom " + encoded));
        assertTrue(DangerousCommands.isDangerous("powershell -EncodedComman " + encoded));
    }

    @Test
    void benignCommandsAreNotFlagged() {
        assertFalse(DangerousCommands.isDangerous("ls -la"));
        assertFalse(DangerousCommands.isDangerous("echo hello world"));
        assertFalse(DangerousCommands.isDangerous(null));
        assertFalse(DangerousCommands.isDangerous(""));
        // 16-char alnum token (decodes as base64) that is not a dangerous command must not false-positive.
        assertFalse(DangerousCommands.isDangerous("git checkout abcdef1234567890"));
        // An -enc flag with a short, non-payload argument (e.g. a charset name) is benign.
        assertFalse(DangerousCommands.isDangerous("javac -encoding utf8 Foo.java"));
    }

    @Test
    void powershellEncodedCommandIsDecodedAndFlagged() {
        // -EncodedCommand payloads are UTF-16LE base64. A recursive delete hidden this way must be caught.
        String encoded = b64Utf16("Remove-Item C:/Users/me/world -Recurse -Force");
        assertTrue(DangerousCommands.isDangerous("powershell -EncodedCommand " + encoded));
        assertTrue(DangerousCommands.isDangerous("powershell -enc " + encoded));
    }

    @Test
    void encodedBenignCommandIsNotFlagged() {
        String encoded = b64Utf16("Get-Date");
        assertFalse(DangerousCommands.isDangerous("powershell -EncodedCommand " + encoded));
    }

    @Test
    void bareBase64DangerousPayloadIsFlagged() {
        // `... | base64 -d | sh` style. The blob must be >= 16 chars to be inspected.
        String blob = b64Utf8("rm -rf /home/user/data");
        assertTrue(DangerousCommands.isDangerous("echo " + blob + " | base64 -d | sh"));
    }

    @Test
    void doubleBase64IsDecodedRecursively() {
        // base64(base64("rm -rf ...")) must not slip past a single decode level.
        String once = b64Utf8("rm -rf /var/lib/important");
        String twice = b64Utf8(once);
        assertTrue(DangerousCommands.isDangerous("echo " + twice + " | base64 -d | base64 -d | sh"));
    }
}
