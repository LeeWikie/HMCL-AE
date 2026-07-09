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
package org.jackhuang.hmcl.ai;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Defines the approval / execution mode for AI agent actions.
///
/// ## History: from a SAFE/ASK/YOLO three-way pick to a single Auto mode
///
/// This used to be a three-way user choice: `SAFE` (only read-only/safe tools auto-run), `ASK`
/// (tools run automatically except dangerous ones, which always ask), and `YOLO` (everything
/// auto-runs, no questions asked). In practice `SAFE` and `ASK` had already converged to the exact
/// same enforcement ({@link org.jackhuang.hmcl.ai.tools.AiExecutionPolicy} treated them
/// identically), and `YOLO`'s unconditional "skip every confirmation" behavior was a standing
/// safety hole: nothing stopped a destructive command from auto-running while nobody was actually
/// watching the conversation (e.g. the follow-up turn the agent fires automatically once a
/// background job completes).
///
/// The three options are now merged into a single mode, `AUTO`:
/// - Read-only, network, and ordinary (controlled) writes still run automatically — the
///   low-friction experience `SAFE`/`ASK`/`YOLO` all already shared for non-dangerous work.
/// - Dangerous operations (see {@link org.jackhuang.hmcl.ai.tools.DangerousCommands} /
///   {@link org.jackhuang.hmcl.ai.tools.CriticalOperations}) still ask for confirmation by default
///   (the old `SAFE`/`ASK` behavior) — Auto only skips that ask when the user has separately turned
///   the "dangerous confirmation" toggle off, mirroring the old opt-in permissive setting.
/// - The one behavior that is now NON-NEGOTIABLE, and can no longer be selected away by picking a
///   more permissive mode: when the current turn may be running unattended (nobody is necessarily
///   watching to answer a prompt — see {@code AiExecutionPolicy}'s `unattended` parameter, driven by
///   whether the turn was triggered by a direct, just-now user message or by a synthetic
///   auto-continuation), a dangerous operation is hard-BLOCKed instead of merely asked. There is no
///   longer any "just pick a looser mode" escape hatch for that specific case — see
///   {@link org.jackhuang.hmcl.ai.tools.AiExecutionPolicy#check(String, String, org.jackhuang.hmcl.ai.tools.ToolPermission, boolean, boolean)}.
///
/// ## Serialization
///
/// The mode is serialized/deserialized via its `id` string (lowercase). Use {@link #fromId(String)}
/// to look up a mode and {@link #getId()} to persist it. Old persisted values of `"safe"`, `"ask"`,
/// and `"yolo"` are still accepted by {@link #fromId(String)} and silently resolve to {@link #AUTO}
/// so existing users' settings files keep loading correctly.
@NotNullByDefault
public enum AiApprovalMode {

    /// The one and only mode: read-only/network/controlled-write tools run automatically;
    /// dangerous operations ask for confirmation (unless that toggle is off) while attended, and
    /// are hard-blocked outright while the turn may be unattended.
    AUTO("auto");

    private final String id;

    AiApprovalMode(String id) {
        this.id = id;
    }

    /// Returns the short identifier string (currently always `"auto"`).
    public String getId() {
        return id;
    }

    /// Returns a human-readable display name for the UI.
    public String getDisplayName() {
        return "Auto";
    }

    /// Looks up an approval mode by its id string.
    ///
    /// @param id the id string (case-insensitive); may be `null`. The legacy ids `"safe"`,
    ///           `"ask"`, and `"yolo"` are accepted for backward compatibility with settings
    ///           files written before the SAFE/ASK/YOLO merge and all resolve to {@link #AUTO}.
    /// @return the matching mode; always {@link #AUTO} today
    public static AiApprovalMode fromId(@Nullable String id) {
        return AUTO;
    }
}
