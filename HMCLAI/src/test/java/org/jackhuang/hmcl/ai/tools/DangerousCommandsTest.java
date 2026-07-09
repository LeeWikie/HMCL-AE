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

    /// A PowerShell backtick line-continuation makes the following newline part of the SAME
    /// statement — the recursive-delete pattern's exclusion class previously excluded \r\n,
    /// letting this evade detection even though it is the exact same command as
    /// "Remove-Item -Recurse -Force <path>" on one line.
    @Test
    void backtickContinuedRemoveItemIsFlagged() {
        assertTrue(DangerousCommands.isDangerous("Remove-Item `\r\n  -Recurse -Force C:\\Users\\me\\world"));
    }

    /// Modern PowerShell Storage-module cmdlets that do the same thing as legacy format/diskpart.
    @Test
    void powershellStorageCmdletsAreFlagged() {
        assertTrue(DangerousCommands.isDangerous("Format-Volume -DriveLetter D -FileSystem NTFS -Confirm:$false"));
        assertTrue(DangerousCommands.isDangerous("Clear-Disk -Number 1 -RemoveData -Confirm:$false"));
        assertTrue(DangerousCommands.isDangerous("Remove-Partition -DiskNumber 1 -PartitionNumber 2"));
        assertTrue(DangerousCommands.isDangerous("Initialize-Disk -Number 1"));
    }

    /// PowerShell's own power-control cmdlets, the native equivalents of shutdown/reboot.
    @Test
    void powershellPowerControlCmdletsAreFlagged() {
        assertTrue(DangerousCommands.isDangerous("Stop-Computer -Force"));
        assertTrue(DangerousCommands.isDangerous("Restart-Computer -Force"));
    }

    /// Windows-specific catastrophic ops with no bash counterpart: shadow-copy wipe (the standard
    /// first step ransomware takes to block System Restore/File History recovery) and disabling
    /// the Windows Recovery Environment.
    @Test
    void windowsBackupAndRecoveryDestructionIsFlagged() {
        assertTrue(DangerousCommands.isDangerous("vssadmin delete shadows /all /quiet"));
        assertTrue(DangerousCommands.isDangerous("bcdedit /set {default} recoveryenabled no"));
        assertTrue(DangerousCommands.isDangerous("wbadmin delete backup -keepVersions:0"));
    }

    /// Bypass #1: PowerShell indirect invocation — the verb is assembled via a variable, string
    /// concatenation, or the `-f` format operator and invoked through `&`/`iex`, so no dangerous verb
    /// ever appears contiguously in the text. Fail-closed: any such construct is dangerous outright.
    @Test
    void powershellIndirectInvocationIsFlagged() {
        assertTrue(DangerousCommands.isDangerous(
                "$v='Remo'+'ve-Item'; & $v -Recurse -Force C:\\Users\\me\\world"));
        assertTrue(DangerousCommands.isDangerous(
                "& ('{0}{1}' -f 'Remove','-Item') -Recurse -Force C:\\Users\\me\\world"));
        assertTrue(DangerousCommands.isDangerous(
                "$op='ri'; & $op -Recurse -Force C:\\Users\\me\\world"));
        assertTrue(DangerousCommands.isDangerous(
                "iex ('Remo'+'ve-Item C:\\Users\\me\\world -Recurse -Force')"));
    }

    /// Bypass #2: mid-word verb splitting — a shell no-op (bash backslash-escape, empty ''/"" quote
    /// run, cmd.exe `^` escape) spliced into the middle of a verb defeats a literal-verb regex.
    @Test
    void midWordVerbSplittingIsFlagged() {
        assertTrue(DangerousCommands.isDangerous("r\\m -rf /path"));
        assertTrue(DangerousCommands.isDangerous("r\"\"m -rf /path"));
        assertTrue(DangerousCommands.isDangerous("r''m -rf /path"));
        assertTrue(DangerousCommands.isDangerous("r^d /s /q C:\\Users\\me\\.minecraft\\saves"));
        assertTrue(DangerousCommands.isDangerous("d^el /s /q C:\\Users\\me\\.minecraft\\saves"));
    }

    /// Bypass #3: PowerShell enumerate-then-pipe-delete — Get-ChildItem gathers a recursive file
    /// list and pipes it into Remove-Item; the recurse flag and the delete verb are on opposite
    /// sides of `|`, evading both the plain verb check and the recursive Remove-Item pattern (which
    /// excludes `|` between the verb and its flag). Dangerous against ANY directory.
    @Test
    void enumerateThenPipeDeleteIsFlagged() {
        assertTrue(DangerousCommands.isDangerous(
                "Get-ChildItem C:\\Users\\me\\.minecraft\\saves -Recurse -File | Remove-Item -Force"));
        assertTrue(DangerousCommands.isDangerous(
                "gci D:\\projects -Recurse | rm"));
    }

    /// Bypass #4: bash variable indirection of the command word — the verb is assigned to a
    /// variable and invoked through `$name` later in the line, so no `rm ` (verb + whitespace)
    /// ever appears contiguously.
    @Test
    void bashVariableIndirectionOfVerbIsFlagged() {
        assertTrue(DangerousCommands.isDangerous("x=rm; $x -rf /home/user/data"));
        assertTrue(DangerousCommands.isDangerous("a=rm; b=-rf; $a $b /home/user/data"));
    }

    /// Bypass #5: wildcard delete with `-Force` but no explicit recurse flag — deletes every file in
    /// the directory just as surely as `-Recurse`, but the recursive Remove-Item pattern requires an
    /// explicit `-r...` flag and misses it.
    @Test
    void wildcardForceDeleteWithNoRecurseFlagIsFlagged() {
        assertTrue(DangerousCommands.isDangerous(
                "Remove-Item C:\\Users\\me\\.minecraft\\saves\\* -Force"));
    }

    /// Bypass #6: bash `${IFS}` (and the further-obfuscated `$IFS$9`) used as a whitespace
    /// substitute defeats the `\s+` requirement in the GNU `rm` pattern.
    @Test
    void ifsWhitespaceSubstitutionIsFlagged() {
        assertTrue(DangerousCommands.isDangerous("rm${IFS}-rf${IFS}/home/user/data"));
        assertTrue(DangerousCommands.isDangerous("rm$IFS$9-rf$IFS$9/home/user/data"));
    }
}
