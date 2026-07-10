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
/// ## History, part 1: SAFE/ASK/YOLO merged into a single Auto mode
///
/// This used to be a three-way user choice: `SAFE` (only read-only/safe tools auto-run), `ASK`
/// (tools run automatically except dangerous ones, which always ask), and `YOLO` (everything
/// auto-runs, no questions asked). At the time, `SAFE` and `ASK` had already converged to the exact
/// same enforcement ({@link org.jackhuang.hmcl.ai.tools.AiExecutionPolicy} treated them
/// identically), and `YOLO`'s unconditional "skip every confirmation" behavior was a standing
/// safety hole: nothing stopped a destructive command from auto-running while nobody was actually
/// watching the conversation (e.g. the follow-up turn the agent fires automatically once a
/// background job completes). So the three options were merged into a single mode, `AUTO`:
/// read-only/network/controlled-write tools ran automatically, dangerous operations asked by
/// default (skippable via a toggle) while attended, and — the one behavior carved out as
/// genuinely non-negotiable — a possibly-unattended turn hard-BLOCKed a DANGEROUS_WRITE outright
/// instead of asking or silently allowing it.
///
/// ## History, part 2: restored to a three-way Auto / Ask / yolo pick
///
/// The single-mode design turned out to remove a choice some users actually wanted: some people
/// want every single action to prompt them, no matter how low-risk it looks — the whole point of
/// choosing something stricter than Auto — and some people who understand the risk want to blast
/// through everything, dangerous operations included, without being nagged. So the mode is a
/// three-way pick again:
/// - `AUTO` (default, id `"auto"`) — unchanged from the single-mode era; see
///   {@link org.jackhuang.hmcl.ai.tools.AiExecutionPolicy} for its exact table.
/// - `ASK` (id `"ask"`) — the maximally conservative pick: nearly every call asks for confirmation,
///   not just the dangerous ones. This is intentionally the "have to click through everything"
///   mode for users who want it.
/// - `YOLO` (id `"yolo"`, {@link #getDisplayName()} deliberately the lowercase string `"yolo"` — a
///   stylistic label, not a typo) — the old YOLO semantics restored: nearly everything, including
///   dangerous operations while attended, auto-runs without asking.
///
/// The one thing that did NOT come back, and must never come back, is YOLO's original unconditional
/// bypass of the unattended-turn safety net: even `YOLO` cannot downgrade or skip the hard-BLOCK on
/// a DANGEROUS_WRITE reached while the turn may be running unattended. That rule was the sole
/// genuine safety motivation for the original merge, it does not depend on how many modes exist, and
/// restoring the three-way pick must not relax it. See
/// {@link org.jackhuang.hmcl.ai.tools.AiExecutionPolicy#check(String, String, org.jackhuang.hmcl.ai.tools.ToolPermission, boolean, boolean)}
/// for where it's enforced, and that class's own doc for the full per-mode decision table.
///
/// ## Serialization
///
/// The mode is serialized/deserialized via its `id` string (lowercase). Use {@link #fromId(String)}
/// to look up a mode and {@link #getId()} to persist it. The legacy persisted value `"safe"` (from
/// before the original SAFE/ASK/YOLO merge) is still accepted by {@link #fromId(String)} and
/// resolves to {@link #ASK} — `SAFE` and `ASK` had already converged to the same enforcement back
/// then, so there is nothing to distinguish them by. The ids `"auto"`, `"ask"`, and `"yolo"` all
/// round-trip to their own same-named mode.
@NotNullByDefault
public enum AiApprovalMode {

    /// Default: read-only/network/controlled-write tools run automatically; dangerous operations
    /// ask for confirmation (unless that toggle is off) while attended, and are hard-blocked
    /// outright while the turn may be unattended.
    AUTO("auto", "Auto"),

    /// The maximally conservative pick: nearly every call asks for confirmation, regardless of how
    /// low-risk it looks. See {@link org.jackhuang.hmcl.ai.tools.AiExecutionPolicy}'s class doc for
    /// the full decision table.
    ASK("ask", "Ask"),

    /// The permissive pick: nearly everything auto-runs without asking, including dangerous
    /// operations while attended — the old YOLO semantics. Still subject to the non-negotiable
    /// unattended DANGEROUS_WRITE hard-BLOCK; see
    /// {@link org.jackhuang.hmcl.ai.tools.AiExecutionPolicy}'s class doc.
    YOLO("yolo", "yolo");

    private final String id;
    private final String displayName;

    AiApprovalMode(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /// Returns the short identifier string (`"auto"`, `"ask"`, or `"yolo"`).
    public String getId() {
        return id;
    }

    /// Returns a human-readable display name for the UI. `YOLO`'s is deliberately the lowercase
    /// string `"yolo"` — a stylistic label, not a typo.
    public String getDisplayName() {
        return displayName;
    }

    /// Looks up an approval mode by its id string.
    ///
    /// @param id the id string (case-insensitive); may be {@code null}, in which case {@link #AUTO}
    ///           is returned. The legacy id `"safe"` (which predates this enum's own id space, from
    ///           before the original SAFE/ASK/YOLO merge) is accepted for backward compatibility and
    ///           resolves to {@link #ASK}, since `SAFE` and `ASK` were already enforced identically.
    /// @return the matching mode, or {@link #AUTO} if {@code id} is {@code null} or unrecognized
    public static AiApprovalMode fromId(@Nullable String id) {
        if (id == null) {
            return AUTO;
        }
        if ("safe".equalsIgnoreCase(id)) {
            return ASK;
        }
        for (AiApprovalMode mode : values()) {
            if (mode.id.equalsIgnoreCase(id)) {
                return mode;
            }
        }
        return AUTO;
    }
}
