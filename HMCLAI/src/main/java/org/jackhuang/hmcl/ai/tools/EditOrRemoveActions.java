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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/// Classifies a CONTROLLED_WRITE tool call as EDITING or REMOVING state that already exists,
/// versus CREATING something the user didn't have before — used by {@link AiExecutionPolicy} to
/// decide whether {@code fileWriteConfirmEnabled} (default OFF, for low-friction routine additions)
/// is allowed to suppress the confirmation prompt.
///
/// # The "new" vs "edit/remove" principle
///
/// `fileWriteConfirmEnabled=false` may ONLY suppress confirmation for PURE CREATION — installing a
/// new mod/modpack/resourcepack/shader, downloading a JDK, creating a new instance/world/account,
/// exporting/backing up to a new file. It must NEVER suppress confirmation for an action that EDITS
/// or REMOVES something that already existed before the call — renaming, reconfiguring, toggling,
/// deleting, or otherwise overwriting existing state ALWAYS prompts, regardless of the toggle. (Note
/// this tier is strictly BELOW {@link CriticalOperations}'s red tier: any action already classified
/// DANGEROUS_WRITE — e.g. `instance` `delete`/`mods_delete`/`mods_update` — is gated by the
/// dangerous-confirmation toggle instead and, for the ones in {@code CriticalOperations
/// .CRITICAL_ACTIONS}, by the extra red confirmation on top; it never reaches this class.)
///
/// # Adding a new tool action
///
/// When a new CONTROLLED_WRITE action is added to a merged domain tool, ask: "does this call modify
/// or delete something that already existed before it ran?" If yes, add its {@code toolName}/
/// {@code action} pair here (or, for a tool with no `action` parameter that is ALWAYS an edit of an
/// existing target — e.g. {@code edit}, an in-place substring replace — add the tool name mapped to
/// an EMPTY set, mirroring {@link CriticalOperations#CRITICAL_ACTIONS}'s "whole tool" convention).
/// If no, leave it out — it stays governed by {@code fileWriteConfirmEnabled} as before.
@NotNullByDefault
public final class EditOrRemoveActions {

    private EditOrRemoveActions() {
    }

    private static final Map<String, Set<String>> EDIT_OR_REMOVE = Map.ofEntries(
            // instance: renaming / reconfiguring / toggling an EXISTING instance or mod, or
            // deleting old log files. Deliberately EXCLUDES create, mods_install,
            // resourcepacks_install/shaders_install/modpacks_install, worlds_import,
            // worlds_backup_create, worlds_datapacks_install, modpacks_export and download_java —
            // every one of those introduces something new rather than mutating/removing something
            // that already existed, so they stay governed by fileWriteConfirmEnabled. open_folder
            // is excluded too (it only creates a directory if missing and opens a file browser; it
            // doesn't mutate/delete any user content). delete/mods_delete/mods_update/worlds_delete/
            // worlds_backup_restore are already DANGEROUS_WRITE (see InstanceTool.getPermission)
            // and never reach this CONTROLLED_WRITE-only check.
            Map.entry("instance", Set.of("rename", "set_memory", "set_isolation", "set_option", "mods_toggle", "clean_logs")),
            // account: set_skin overwrites an EXISTING account's skin/cape. add_offline creates a
            // new account, select only switches which already-logged-in account is active, and
            // microsoft_login just opens the native sign-in dialog — none of those three mutate or
            // remove existing account state, so they stay governed by fileWriteConfirmEnabled.
            Map.entry("account", Set.of("set_skin")),
            // edit: in-place substring replace inside an file that must ALREADY exist (see
            // EditTool#execute, which fails outright if the target is missing) — this tool has no
            // `action` parameter, so the empty set marks the whole tool, mirroring
            // CriticalOperations.CRITICAL_ACTIONS' "whole tool" convention.
            Map.entry("edit", Set.of()));

    /// Returns whether {@code toolName}'s call with the given {@code action} (may be {@code null}
    /// or blank for a tool with no action parameter) edits or removes pre-existing state, per the
    /// curated set above. Unknown tools/actions default to {@code false} (i.e. they stay governed
    /// by {@code fileWriteConfirmEnabled} exactly as before this classification existed).
    public static boolean isEditOrRemove(String toolName, @Nullable String action) {
        Set<String> actions = EDIT_OR_REMOVE.get(toolName.toLowerCase(Locale.ROOT));
        if (actions == null) {
            return false;
        }
        if (actions.isEmpty()) {
            return true; // whole-tool entry (no action parameter)
        }
        String a = action != null ? action.trim().toLowerCase(Locale.ROOT) : "";
        return actions.contains(a);
    }
}
