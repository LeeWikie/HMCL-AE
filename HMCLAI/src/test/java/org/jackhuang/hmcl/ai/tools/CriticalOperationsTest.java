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
        assertNotNull(CriticalOperations.criticalReason("delete_world", "{}"));
        assertNotNull(CriticalOperations.criticalReason("delete_instance", "{}"));
        assertNotNull(CriticalOperations.criticalReason("set_nbt", "{}"));
        assertNotNull(CriticalOperations.criticalReason("restore_world_backup", "{}"));
    }

    @Test
    void ordinaryToolsAreNotCritical() {
        assertNull(CriticalOperations.criticalReason("list_instances", "{}"));
        assertNull(CriticalOperations.criticalReason("read_world_info", "{\"world\":\"x\"}"));
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
}
