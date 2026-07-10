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

import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/// Shared helper for the world/save-domain AI tools (delete_world / install_datapack /
/// list_datapacks / world backups / the NBT save-editing suite) — the world-level sibling of
/// {@link InstanceToolSupport}.
///
/// Its one job is the unified "world (save folder) not found" failure: instead of a bare
/// `"World 'X' was not found at: <path>"` sentence, it carries the data the model is actually
/// looking for — the REAL world folder names under `saves/` — in the {@link ToolFailures}
/// envelope, exactly the way {@link InstanceToolSupport#instanceNotFoundFailure} /
/// {@link InstanceToolSupport#availableInstanceNames} carry the real instance names for the
/// "instance not found" line. This closes the structurally-identical "world not found" parallel
/// so a mistyped save name fails once with the candidate list rather than sending the model into
/// blind retry.
///
/// The candidate list is produced by [#availableWorldNames(Path)] so the tools whose failure type
/// is not a {@link ToolResult} (the backup engine throws `IOException`, the NBT suite throws its
/// own checked exception) can embed the same envelope text via [#worldNotFoundEnvelope(Path,
/// String)]. Building the list must never itself throw.
@NotNullByDefault
final class WorldToolSupport {

    private WorldToolSupport() {
    }

    /// A ready {@link ToolResult#failure} for a missing world, for the tools that return a
    /// {@link ToolResult} directly (delete_world / install_datapack / list_datapacks).
    ///
    /// @param savesDir the instance's `saves/` directory (its child folders are the candidates)
    /// @param world    the save folder name the caller asked for and could not find
    static ToolResult worldNotFoundFailure(Path savesDir, String world) {
        return ToolResult.failure(worldNotFoundEnvelope(savesDir, world));
    }

    /// The unified "world does not exist" failure envelope, carrying the real world folder names
    /// under `saves/` (the candidate list the model is looking for). Kept as plain text — not a
    /// {@link ToolResult} — so the callers that surface failures through an exception message
    /// (the backup engine's `IOException`, the NBT suite's `NbtToolException`) can reuse the exact
    /// same wording. The `"was not found"` phrasing is retained verbatim so it stays consistent
    /// with the pre-existing world tools (and their tests).
    ///
    /// @param savesDir the instance's `saves/` directory
    /// @param world    the save folder name that could not be found
    static String worldNotFoundEnvelope(Path savesDir, String world) {
        return ToolFailures.failureEnvelope(
                "World '" + world + "' was not found under the instance's saves/ directory",
                ToolFailures.Retryable.YES,
                "the name is wrong, not a missing world — use an exact saves/ folder name",
                "existing worlds: " + availableWorldNames(savesDir)
                        + ". Use instance(action=\"worlds_list\") to refresh, or retry with an exact folder name");
    }

    /// Comma-joined names of the world folders directly under {@code savesDir} (each immediate
    /// sub-directory is a candidate world), capped so a huge saves/ directory can't flood a
    /// tool-error message. Hidden entries whose name starts with `'.'` are skipped — those are
    /// the interrupted-restore leftovers ({@code .{world}.replaced} / {@code .{world}.restoring}
    /// / {@code .{world}.restore-in-progress}) that {@link WorldBackupManager} strands, never real
    /// worlds. Package-visible so the whole world/save tool suite shares one candidate list.
    ///
    /// Never throws: a missing/unreadable saves/ directory degrades to a safe placeholder rather
    /// than turning error-message construction into a second failure.
    static String availableWorldNames(Path savesDir) {
        if (!Files.isDirectory(savesDir)) {
            return "(none — this instance has no worlds under saves/)";
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> children = Files.list(savesDir)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                if (names.size() >= 21) {
                    break;
                }
                if (!Files.isDirectory(child)) {
                    continue;
                }
                String name = String.valueOf(child.getFileName());
                if (name.startsWith(".")) {
                    continue; // interrupted-restore leftover / hidden dir, not a world
                }
                names.add(name);
            }
        } catch (Throwable e) {
            return "(unavailable — the saves/ directory could not be listed)";
        }
        if (names.isEmpty()) {
            return "(none — this instance has no worlds under saves/)";
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        if (names.size() > 20) {
            return String.join(", ", names.subList(0, 20))
                    + ", … (use instance(action=\"worlds_list\") for the full list)";
        }
        return String.join(", ", names);
    }
}
