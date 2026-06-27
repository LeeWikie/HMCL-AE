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
/// Each mode governs how the agent handles tool calls and potentially
/// dangerous operations:
/// - `SAFE` — the agent only executes built-in read-only or safe tools
///   without prompting the user; everything else is rejected or requires
///   the user to manually approve.
/// - `ASK` — the agent asks for confirmation before executing any
///   non-trivial tool call, including write operations.
/// - `YOLO` — the agent runs every tool call without asking;
///   intended for advanced users who trust the model's output.
///
/// ## Serialization
///
/// The mode is serialized/deserialized via its `id` string
/// (lowercase). Use {@link #fromId(String)} to look up a mode
/// and {@link #getId()} to persist it.
@NotNullByDefault
public enum AiApprovalMode {

    /// Safe mode: only read-only tools are auto-executed.
    SAFE("safe"),
    /// Ask mode: the user must confirm each non-trivial tool call.
    ASK("ask"),
    /// YOLO mode: the agent auto-executes every tool call.
    YOLO("yolo");

    private final String id;

    AiApprovalMode(String id) {
        this.id = id;
    }

    /// Returns the short identifier string (e.g. `"safe"`, `"ask"`, `"yolo"`).
    public String getId() {
        return id;
    }

    /// Returns a human-readable display name for the UI.
    public String getDisplayName() {
        return switch (this) {
            case SAFE -> "Safe";
            case ASK -> "Ask";
            case YOLO -> "YOLO";
        };
    }

    /// Looks up an approval mode by its id string.
    ///
    /// @param id the id string (case-insensitive); may be `null`
    /// @return the matching mode, or the {@link #SAFE} default if no match
    public static AiApprovalMode fromId(@Nullable String id) {
        if (id == null) {
            return SAFE;
        }
        for (AiApprovalMode mode : values()) {
            if (mode.id.equalsIgnoreCase(id)) {
                return mode;
            }
        }
        return SAFE;
    }
}
