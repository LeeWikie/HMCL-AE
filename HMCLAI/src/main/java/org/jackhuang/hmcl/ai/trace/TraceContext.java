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
package org.jackhuang.hmcl.ai.trace;

import org.jetbrains.annotations.Nullable;

/// Identifies which session + user-turn a trace event belongs to, so the adapter (which does
/// not otherwise know the session) can tag every request/response/tool/guard event it records.
/// A turn = one user input; a turn spans multiple model-call cycles inside the tool loop.
///
/// {@link #NONE} is the disabled sentinel — when the host has not begun a turn, the adapter
/// carries this and every {@code TraceRecorder.record} call is a no-op (blank session id).
public record TraceContext(@Nullable String sessionId, @Nullable String turnId) {

    public static final TraceContext NONE = new TraceContext(null, null);

    public boolean active() {
        return sessionId != null && !sessionId.isBlank();
    }
}
