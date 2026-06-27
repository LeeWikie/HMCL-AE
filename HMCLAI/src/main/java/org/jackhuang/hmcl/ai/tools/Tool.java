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

/// An AI-accessible tool that can be invoked by the chat agent.
///
/// Each tool has a unique [`name`], a human-readable [`description`] shown to the model,
/// and an [`execute`] method that accepts a map of named parameters.
///
/// Implementations should be thread-safe if they are registered in a shared
/// [`ToolRegistry`].
///
/// @see ToolRegistry
/// @see ToolResult
@NotNullByDefault
public interface Tool {

    /// Returns the unique name used to identify this tool.
    ///
    /// The name is used as the key in [`ToolRegistry`] and is exposed to the
    /// LLM in function-calling requests.
    String getName();

    /// Returns a human-readable description of what the tool does.
    ///
    /// This description is sent to the LLM so it can decide when to invoke
    /// the tool. Keep it concise and actionable.
    String getDescription();

    /// Executes the tool with the given named parameters.
    ///
    /// @param parameters a map of parameter names to their values; never `null`,
    ///                   may be empty
    /// @return a [`ToolResult`] indicating success or failure
    ToolResult execute(Map<String, Object> parameters);
}
