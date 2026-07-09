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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Locks in the second-tier (red) critical-operation classifier: catastrophic tool names always
/// qualify, shell commands that delete save/.minecraft paths qualify (raw and base64-encoded), and
/// benign tools/commands do not.
public final class CriticalOperationsTest {

    @Test
    void catastrophicToolNamesAreCritical() {
        assertNotNull(CriticalOperations.criticalReason("instance", "{\"action\":\"delete\"}"));
        assertNotNull(CriticalOperations.criticalReason("instance", "{\"action\":\"worlds_delete\"}"));
        assertNotNull(CriticalOperations.criticalReason("instance", "{\"action\":\"worlds_backup_restore\"}"));
        assertNotNull(CriticalOperations.criticalReason("instance", "{\"action\":\"mods_delete\"}"));
        assertNotNull(CriticalOperations.criticalReason("instance", "{\"action\":\"mods_update\"}"));
        assertNotNull(CriticalOperations.criticalReason("instance", "{\"action\":\"resourcepacks_delete\"}"));
        assertNotNull(CriticalOperations.criticalReason("instance", "{\"action\":\"shaders_delete\"}"));
        assertNotNull(CriticalOperations.criticalReason("nbt", "{\"action\":\"set\"}"));
        assertNotNull(CriticalOperations.criticalReason("nbt", "{\"action\":\"copy_player_data\"}"));
        assertNotNull(CriticalOperations.criticalReason("nbt", "{\"action\":\"transfer_inventory\"}"));
    }

    @Test
    void actionCasingAndWhitespaceDoNotBypassTheCriticalGate() {
        // The model may return a differently-cased or whitespace-padded action string; the
        // critical-op gate must normalize it the same way every domain facade's own dispatch does.
        assertNotNull(CriticalOperations.criticalReason("instance", "{\"action\":\"Delete\"}"));
        assertNotNull(CriticalOperations.criticalReason("instance", "{\"action\":\" delete \"}"));
        assertNotNull(CriticalOperations.criticalReason("nbt", "{\"action\":\"Set\"}"));
    }

    @Test
    void ordinaryToolsAreNotCritical() {
        assertNull(CriticalOperations.criticalReason("instance", "{\"action\":\"list\"}"));
        assertNull(CriticalOperations.criticalReason("instance", "{\"action\":\"worlds_info\",\"world\":\"x\"}"));
        assertNull(CriticalOperations.criticalReason("nbt", "{\"action\":\"read\"}"));
        assertNull(CriticalOperations.criticalReason("nbt", "{\"action\":\"get\"}"));
        assertNull(CriticalOperations.criticalReason("shell", "{\"command\":\"ls -la\"}"));
    }

    @Test
    void shellDeletingSavePathsIsCritical() {
        assertNotNull(CriticalOperations.criticalReason(
                "shell", "{\"command\":\"rm -rf .minecraft/saves/MyWorld\",\"description\":\"x\"}"));
        assertNotNull(CriticalOperations.criticalReason(
                "shell", "{\"command\":\"Remove-Item C:/Users/me/.minecraft/saves -Recurse -Force\"}"));
    }

    @Test
    void base64EncodedShellDeletionOfSavesIsCritical() {
        // The danger must survive being hidden in a PowerShell -EncodedCommand (UTF-16LE base64).
        String encoded = Base64.getEncoder().encodeToString(
                "Remove-Item C:/Users/me/.minecraft/saves -Recurse -Force".getBytes(StandardCharsets.UTF_16LE));
        String argsJson = "{\"command\":\"powershell -EncodedCommand " + encoded + "\"}";
        assertNotNull(CriticalOperations.criticalReason("shell", argsJson));
    }

    /// Bypass #1 also breaks the RED gate: the verb is assembled via string concatenation and
    /// invoked indirectly through `& $v`, so the literal-verb DELETE_VERB regex never matches — but
    /// the command still deletes the saves directory. Fail-closed: an indirect-invocation construct
    /// combined with a critical path must raise the red confirmation.
    @Test
    void indirectInvocationDeletingSavesIsCritical() {
        assertNotNull(CriticalOperations.criticalReason(
                "shell", "{\"command\":\"$v='Remo'+'ve-Item'; & $v -Recurse -Force C:/Users/me/.minecraft/saves\"}"));
    }

    /// Bypass #2 also breaks the RED gate: cmd.exe `^` splits the delete verb across the escape
    /// character, so the literal-verb regex never matches even though the target is a critical path.
    @Test
    void midWordVerbSplittingDeletingSavesIsCritical() {
        assertNotNull(CriticalOperations.criticalReason(
                "shell", "{\"command\":\"r^d /s /q C:\\\\Users\\\\me\\\\.minecraft\\\\saves\"}"));
    }
}
