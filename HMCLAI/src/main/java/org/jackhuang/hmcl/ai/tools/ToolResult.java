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

/// The result of executing a [`Tool`].
///
/// Each result carries a [`success`] flag, the text [`output`] produced by the tool,
/// and an optional [`error`] message when the tool fails.
///
/// Use the static factory methods [`ToolResult#success`] and [`ToolResult#failure`]
/// rather than calling the constructor directly.
///
/// @see Tool
@NotNullByDefault
public final class ToolResult {

    /// Whether the tool execution completed successfully.
    private final boolean success;

    /// The text output produced by the tool.
    private final String output;

    /// An error message describing the failure, or `null` when successful.
    @Nullable
    private final String error;

    /// Creates a result with the given success state, output, and optional error.
    ///
    /// @param success whether the execution succeeded
    /// @param output  the text output; must not be `null`
    /// @param error   an error description; may be `null` for successful results
    private ToolResult(boolean success, String output, @Nullable String error) {
        this.success = success;
        this.output = output;
        this.error = error;
    }

    /// Creates a successful result with the given output text.
    ///
    /// @param output the text output produced by the tool; must not be `null`
    /// @return a new successful result
    public static ToolResult success(String output) {
        return new ToolResult(true, output, null);
    }

    /// Creates a failure result with the given error message.
    ///
    /// @param error a description of the failure; must not be `null`
    /// @return a new failure result
    public static ToolResult failure(String error) {
        return new ToolResult(false, "", error);
    }

    /// Returns whether the tool execution succeeded.
    public boolean isSuccess() {
        return success;
    }

    /// Returns the text output produced by the tool.
    public String getOutput() {
        return output;
    }

    /// Returns the error message, or `null` if the execution succeeded.
    @Nullable
    public String getError() {
        return error;
    }
}
