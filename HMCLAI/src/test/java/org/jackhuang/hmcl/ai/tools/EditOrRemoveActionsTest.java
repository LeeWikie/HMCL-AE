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

/// Locks in the "new vs edit/remove" classification (Part C) used by {@link AiExecutionPolicy} to
/// decide whether a CONTROLLED_WRITE call runs automatically (pure creation) or always asks
/// (edit/remove of pre-existing state).
public final class EditOrRemoveActionsTest {

    @Test
    void instanceEditActionsAreEditOrRemove() {
        assertTrue(EditOrRemoveActions.isEditOrRemove("instance", "rename"));
        assertTrue(EditOrRemoveActions.isEditOrRemove("instance", "set_memory"));
        assertTrue(EditOrRemoveActions.isEditOrRemove("instance", "set_isolation"));
        assertTrue(EditOrRemoveActions.isEditOrRemove("instance", "set_option"));
        assertTrue(EditOrRemoveActions.isEditOrRemove("instance", "mods_toggle"));
        assertTrue(EditOrRemoveActions.isEditOrRemove("instance", "clean_logs"));
    }

    @Test
    void instanceCreateAndInstallActionsAreNotEditOrRemove() {
        // Pure creation/addition — runs automatically without confirmation.
        assertFalse(EditOrRemoveActions.isEditOrRemove("instance", "create"));
        assertFalse(EditOrRemoveActions.isEditOrRemove("instance", "mods_install"));
        assertFalse(EditOrRemoveActions.isEditOrRemove("instance", "resourcepacks_install"));
        assertFalse(EditOrRemoveActions.isEditOrRemove("instance", "shaders_install"));
        assertFalse(EditOrRemoveActions.isEditOrRemove("instance", "modpacks_install"));
        assertFalse(EditOrRemoveActions.isEditOrRemove("instance", "worlds_import"));
        assertFalse(EditOrRemoveActions.isEditOrRemove("instance", "worlds_backup_create"));
        assertFalse(EditOrRemoveActions.isEditOrRemove("instance", "worlds_datapacks_install"));
        assertFalse(EditOrRemoveActions.isEditOrRemove("instance", "modpacks_export"));
        assertFalse(EditOrRemoveActions.isEditOrRemove("instance", "download_java"));
        assertFalse(EditOrRemoveActions.isEditOrRemove("instance", "open_folder"));
    }

    @Test
    void instanceDangerousWriteActionsAreNotClassifiedHere() {
        // delete/mods_delete/mods_update/worlds_delete/worlds_backup_restore are already
        // DANGEROUS_WRITE and never reach this CONTROLLED_WRITE-only classification — this class
        // simply has no opinion on them (returns false), matching "unknown -> false".
        assertFalse(EditOrRemoveActions.isEditOrRemove("instance", "delete"));
        assertFalse(EditOrRemoveActions.isEditOrRemove("instance", "mods_delete"));
    }

    @Test
    void accountSetSkinIsEditOrRemoveButOtherActionsAreNot() {
        assertTrue(EditOrRemoveActions.isEditOrRemove("account", "set_skin"));
        assertFalse(EditOrRemoveActions.isEditOrRemove("account", "add_offline"));
        assertFalse(EditOrRemoveActions.isEditOrRemove("account", "select"));
        assertFalse(EditOrRemoveActions.isEditOrRemove("account", "microsoft_login"));
        assertFalse(EditOrRemoveActions.isEditOrRemove("account", "list"));
    }

    @Test
    void editToolIsWholeToolEditOrRemove() {
        // No `action` parameter at all (null/blank) — the whole tool is an in-place edit.
        assertTrue(EditOrRemoveActions.isEditOrRemove("edit", null));
        assertTrue(EditOrRemoveActions.isEditOrRemove("edit", ""));
        assertTrue(EditOrRemoveActions.isEditOrRemove("edit", "anything"));
    }

    @Test
    void unknownToolsAndActionsDefaultToFalse() {
        assertFalse(EditOrRemoveActions.isEditOrRemove("write", null));
        assertFalse(EditOrRemoveActions.isEditOrRemove("nonexistent-tool", "delete"));
        assertFalse(EditOrRemoveActions.isEditOrRemove("instance", "nonexistent-action"));
    }

    @Test
    void actionMatchingIsCaseAndWhitespaceInsensitive() {
        assertTrue(EditOrRemoveActions.isEditOrRemove("instance", "  RENAME  "));
        assertTrue(EditOrRemoveActions.isEditOrRemove("INSTANCE", "rename"));
    }
}
