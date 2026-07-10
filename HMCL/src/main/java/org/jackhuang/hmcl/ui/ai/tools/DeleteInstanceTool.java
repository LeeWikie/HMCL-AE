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
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/// DANGEROUS: deletes a Minecraft instance (version) from disk.
///
/// Both delete strategies funnel through the single native removal path
/// {@link HMCLGameRepository#removeVersionFromDisk(String, boolean)} (the exact repository call
/// performed by the native delete action in {@code Versions.deleteVersion}), so the
/// {@link org.jackhuang.hmcl.event.RemoveVersionEvent} hook always fires and listeners keep
/// their chance to observe or veto the removal. The user's recycle-bin preference is passed as
/// the {@code moveToTrash} parameter: when it is on, the version directory is offered to the OS
/// recycle bin / trash first (recoverable where the platform supports it); when it is off, the
/// directory is deleted permanently.
///
/// Occupancy guard (see {@link GameResourceGuard}): before anything is deleted the tool refuses
/// when
/// - HMCL itself is tracking a live game process for the instance
///   ({@link GameResourceGuard#checkInstanceNotRunning(String)}), pointing the model at
///   {@code game(action="stop")} first; and, as a fallback for games launched OUTSIDE HMCL
///   (which the process table cannot see),
/// - any world under the version root's {@code saves/} directory holds its {@code session.lock}
///   ({@link GameResourceGuard#checkWorldNotLocked(Path, String)}, the same probe
///   {@link DeleteWorldTool} uses) — only the version root is scanned because that is the
///   directory being deleted; shared (non-isolated) saves outside it are untouched by this
///   operation.
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
                + "Refuses while the instance is running — stop the game first (game(action=\"stop\")). "
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
                    + "Re-invoke instance(action=\"delete\", instance=\"" + instance + "\", confirm=true) to proceed.");
        }

        // Occupancy guard 1: HMCL's own per-instance process table — refuse while a game HMCL
        // launched is still running (its rejection envelope directs the model to
        // game(action="stop") first).
        String rejection = GameResourceGuard.checkInstanceNotRunning(instance);
        if (rejection != null) {
            return ToolResult.failure(rejection);
        }

        // Occupancy guard 2 (external-launch fallback): the process table cannot see games
        // started outside HMCL, but such a session still holds session.lock of the world it has
        // open — probe every world under the version root's saves/ (the directory about to be
        // deleted), mirroring DeleteWorldTool's session.lock guard.
        rejection = checkNoWorldLockedUnderVersionRoot(repository, instance);
        if (rejection != null) {
            return ToolResult.failure(rejection);
        }

        // Single removal path (native, same call as Versions.deleteVersion) so the
        // RemoveVersionEvent hook always fires; the recycle-bin preference selects whether the
        // repository offers the directory to the OS trash before deleting permanently.
        boolean preferTrash = toRecycleBin.getAsBoolean();
        boolean ok = repository.removeVersionFromDisk(instance, preferTrash);
        if (!ok) {
            return ToolFailures.failure(
                    "Failed to remove instance '" + instance + "' from disk — its version directory could not be "
                            + "renamed (files inside it are likely held open by a running process, e.g. a game "
                            + "launched outside HMCL) or a launcher component vetoed the removal; nothing was deleted",
                    ToolFailures.Retryable.LATER,
                    "the removal can succeed once no process holds the instance's files open",
                    "Ask the user to close any game or program using this instance's files, then retry this call");
        }

        repository.refreshVersions();
        if (preferTrash) {
            // The native path offers the directory to the OS trash first, but silently falls back
            // to a permanent delete when the platform has no trash support — report honestly.
            return ToolResult.success("Removed instance '" + instance
                    + "' from disk. The recycle-bin preference is ON, so it was offered to the system recycle bin "
                    + "first and should be recoverable from there where the platform supports it.");
        }
        return ToolResult.success("Permanently deleted instance '" + instance
                + "' from disk (the recycle-bin preference is OFF).");
    }

    /// Probes `session.lock` of every world directory under the version root's `saves/`.
    /// Returns the ready-to-return rejection envelope of the first locked world, or `null` when
    /// no world is locked. Scan errors (unresolvable version root, unreadable directory) skip the
    /// guard rather than blocking a deletion the user explicitly confirmed — this is an advisory
    /// fallback on top of the process-table check.
    @Nullable
    private static String checkNoWorldLockedUnderVersionRoot(HMCLGameRepository repository, String instance) {
        Path savesDir;
        try {
            savesDir = repository.getVersionRoot(instance).resolve("saves");
        } catch (Throwable e) {
            return null;
        }
        if (!Files.isDirectory(savesDir)) {
            return null;
        }
        try (DirectoryStream<Path> worlds = Files.newDirectoryStream(savesDir, Files::isDirectory)) {
            for (Path worldDir : worlds) {
                String rejection = GameResourceGuard.checkWorldNotLocked(
                        worldDir, worldDir.getFileName().toString());
                if (rejection != null) {
                    return rejection;
                }
            }
        } catch (Throwable ignored) {
            // Best-effort scan; unreadable saves/ must not block the confirmed deletion.
        }
        return null;
    }
}
