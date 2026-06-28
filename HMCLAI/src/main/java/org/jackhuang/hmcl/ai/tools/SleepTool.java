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

import java.util.Map;

/// An AI tool that pauses the agent for a bounded number of seconds.
///
/// This is useful when the agent kicks off a long-running operation — such as a mod
/// download or a game/loader installation — and wants to wait for it to make progress
/// before re-checking its status (e.g. by reading a log again).
///
/// The requested duration is clamped to the inclusive range
/// `[0, `{@link #MAX_SECONDS}`]` to keep waits bounded and avoid the agent hanging.
///
/// @see Tool
/// @see ToolResult
@NotNullByDefault
public final class SleepTool implements Tool {

    /// The maximum number of seconds a single sleep may last.
    static final int MAX_SECONDS = 60;

    /// The default number of seconds to wait when the `seconds` parameter is absent.
    static final int DEFAULT_SECONDS = 5;

    /// Returns the unique name `"sleep"`.
    @Override
    public String getName() {
        return "sleep";
    }

    /// Returns a human-readable description of what the tool does.
    @Override
    public String getDescription() {
        return "Pauses for a bounded number of seconds to let a long-running operation "
                + "(e.g. a download or installation) make progress before re-checking it. "
                + "Parameter: seconds (int, clamped to 0.." + MAX_SECONDS
                + ", default " + DEFAULT_SECONDS + ").";
    }

    /// Sleeps for the requested (clamped) number of seconds.
    ///
    /// Supported parameters:
    /// - `seconds` ({@code Integer}/{@code Number}/{@code String}) — how long to wait;
    ///   clamped to `[0, `{@link #MAX_SECONDS}`]`.
    ///
    /// @param parameters a map of named parameters; never `null`
    /// @return a successful result confirming the elapsed wait, or a failure result if
    ///         the wait was interrupted
    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        int seconds = extractInt(parameters, "seconds", DEFAULT_SECONDS);
        seconds = Math.max(0, Math.min(seconds, MAX_SECONDS));

        if (seconds > 0) {
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.failure("Sleep interrupted after requesting " + seconds + " second(s).");
            }
        }

        return ToolResult.success("Waited " + seconds + " second(s).");
    }

    /// Extracts an integer value from the parameters map, falling back to a default.
    ///
    /// @param params   the parameter map; must not be `null`
    /// @param key      the parameter name
    /// @param fallback the default value when the key is absent or unparseable
    /// @return the extracted or default value
    private static int extractInt(Map<String, Object> params, String key, int fallback) {
        Object val = params.get(key);
        if (val instanceof Integer i) {
            return i;
        }
        if (val instanceof Number n) {
            return n.intValue();
        }
        if (val instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
