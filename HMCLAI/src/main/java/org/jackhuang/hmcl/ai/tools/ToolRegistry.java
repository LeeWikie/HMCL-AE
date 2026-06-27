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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// A registry that stores and looks up AI [`Tool`] instances by name.
///
/// Tools are registered with a unique name and can be looked up or listed.
/// The registry is thread-safe for reads after initial registration; concurrent
/// modifications from multiple threads require external synchronization.
///
/// @see Tool
@NotNullByDefault
public final class ToolRegistry {

    /// The backing map of registered tools, keyed by tool name.
    /// Uses [`LinkedHashMap`] to preserve insertion order for [`list`].
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /// Registers a tool in the registry.
    ///
    /// If a tool with the same name already exists, it is replaced.
    ///
    /// @param tool the tool to register; must not be `null`
    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
    }

    /// Looks up a tool by its unique name.
    ///
    /// @param name the name of the tool to find; must not be `null`
    /// @return the registered tool, or `null` if no tool matches the given name
    @Nullable
    public Tool get(String name) {
        return tools.get(name);
    }

    /// Returns all registered tools in insertion order.
    ///
    /// The returned list is a snapshot — subsequent registrations are not reflected.
    ///
    /// @return an unmodifiable list of all registered tools
    public List<Tool> list() {
        return List.copyOf(new ArrayList<>(tools.values()));
    }
}
