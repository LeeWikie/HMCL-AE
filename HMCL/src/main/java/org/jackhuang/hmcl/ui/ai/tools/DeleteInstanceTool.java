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

import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/// DANGEROUS: deletes a Minecraft instance (version) from disk.
///
/// Honours the recycle-bin preference (mirroring {@link DeleteWorldTool}):
/// - When {@code toRecycleBin} is true and the platform supports a recycle bin / trash, the
///   version directory ({@link HMCLGameRepository#getVersionRoot(String)}) is moved to the
///   recycle bin via {@link FileTrash#delete(Path, boolean)} (recoverable), then the repository
///   is refreshed ({@link HMCLGameRepository#refreshVersions()}) so it forgets the version.
/// - Otherwise (preference off, or no trash support, or the version directory cannot be located)
///   it falls back to the exact repository call performed by the native delete action in
///   {@code Versions.deleteVersion}: {@link HMCLGameRepository#removeVersionFromDisk(String)}.
///
/// As a safety net (until a real UI confirmation dialog is wired in), this tool requires
/// an explicit {@code confirm=true} parameter. Without it, nothing is deleted and the tool
/// returns a failure explaining what would be deleted and how to confirm.
@NotNullByDefault
public final class DeleteInstanceTool implements Tool {

    private final java.util.function.BooleanSupplier toRecycleBin;

    /// @param toRecycleBin whether to route deletions to the OS recycle bin (recoverable) instead
    ///                     of permanently deleting; read live on each call. Typically
    ///                     `aiSettings::isDeleteToRecycleBin`.
    public DeleteInstanceTool(java.util.function.BooleanSupplier toRecycleBin) {
        this.toRecycleBin = toRecycleBin;
    }

    @Override
    public String getName() {
        return "delete_instance";
    }

    @Override
    public String getDescription() {
        return "DANGEROUS: DELETE a Minecraft instance (version) from disk. "
                + "Depending on the user's preference this either moves the instance to the system recycle bin "
                + "(recoverable) or permanently removes it (cannot be undone). "
                + "Parameters: instance (instance name to delete; falls back to 'query'), "
                + "confirm (REQUIRED boolean). "
                + "If confirm is not exactly true, NOTHING is deleted and the tool reports what would be removed; "
                + "you must then re-invoke with confirm=true to actually delete. "
                + "Only use this when the user has clearly asked to delete that specific instance.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String instance = InstanceToolSupport.instanceName(parameters);

        if (instance == null) {
            return ToolResult.failure("Missing required parameter: 'instance' (or 'query').");
        }

        HMCLGameRepository repository = InstanceToolSupport.repository();

        if (!repository.isLoaded()) {
            return ToolResult.failure("The game repository is not loaded yet; please try again in a moment.");
        }
        if (!repository.hasVersion(instance)) {
            return ToolResult.failure("No such instance: '" + instance + "'.");
        }

        // Confirm-gate: do NOT delete unless confirm is exactly true.
        if (!InstanceToolSupport.bool(parameters, "confirm")) {
            return ToolResult.failure("Not confirmed: this will DELETE the instance '" + instance
                    + "' and all of its files from disk. If the recycle-bin preference is off this is IRREVERSIBLE. "
                    + "Re-invoke delete_instance with confirm=true to proceed.");
        }

        // Recoverable path: move the version's on-disk directory to the recycle bin, then let the
        // repository rebuild its version list from disk so it forgets the now-removed version.
        if (toRecycleBin.getAsBoolean() && FileTrash.trashSupported()) {
            Path versionDir;
            try {
                versionDir = repository.getVersionRoot(instance);
            } catch (Throwable e) {
                versionDir = null;
            }

            if (versionDir != null && Files.isDirectory(versionDir)) {
                try {
                    boolean trashed = FileTrash.delete(versionDir, true);
                    repository.refreshVersions();
                    if (trashed) {
                        return ToolResult.success("Moved instance '" + instance
                                + "' to the system recycle bin (recoverable).\nPath: " + versionDir);
                    }
                    // FileTrash fell back to a permanent delete (the OS rejected the move-to-trash).
                    return ToolResult.success("Permanently deleted instance '" + instance
                            + "' from disk: the OS rejected the recycle-bin move, so it was removed permanently.\nPath: "
                            + versionDir);
                } catch (Throwable e) {
                    // Could not move/delete the directory directly; fall back to the native path below.
                }
            }
            // versionDir could not be located on disk: fall back to the native path below.
        }

        // Permanent / native path: reuses the exact repository call from Versions.deleteVersion.
        boolean ok = repository.removeVersionFromDisk(instance);
        if (!ok) {
            return ToolResult.failure("Failed to delete instance '" + instance
                    + "' from disk (an I/O error occurred).");
        }

        repository.refreshVersions();
        // HMCL's native removeVersionFromDisk itself attempts a move-to-trash where the platform
        // supports it, so the removal may in fact be recoverable — report honestly rather than
        // claiming an irreversible permanent delete.
        return ToolResult.success("Removed instance '" + instance
                + "' from disk (it may be recoverable from the system recycle bin where supported).");
    }
}
