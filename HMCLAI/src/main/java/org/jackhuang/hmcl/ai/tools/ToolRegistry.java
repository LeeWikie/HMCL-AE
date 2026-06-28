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

/// A registry that stores and looks up AI [`Tool`] instances by name,
/// with optional metadata (permission, source, enabled/disabled).
///
/// Tools are registered with a unique name and can be looked up or listed.
/// The registry is thread-safe for reads after initial registration; concurrent
/// modifications from multiple threads require external synchronization.
///
/// # Metadata support
///
/// Tools that implement {@link ToolSpec} declare their own
/// {@link ToolPermission} and {@link ToolSource}.  Tools that only
/// implement {@link Tool} default to {@link ToolPermission#CONTROLLED_WRITE}
/// and {@link ToolSource#LOCAL}.
///
/// # Enable / disable
///
/// Disabled tools are excluded from {@link #list()} so adapters
/// ({@code LangChain4jToolAdapter}) never expose them to the model.
///
/// @see Tool
/// @see ToolSpec
/// @see ToolPermission
/// @see ToolSource
@NotNullByDefault
public final class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final java.util.Set<String> disabledTools = new java.util.HashSet<>();

    /// Registers a tool. If a tool with the same name exists, it is replaced.
    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
    }

    /// Looks up a tool by name.
    @Nullable
    public Tool get(String name) {
        return tools.get(name);
    }

    /// Returns the tool's permission. Tools implementing {@link ToolSpec}
    /// declare their own; others default to {@link ToolPermission#CONTROLLED_WRITE}.
    public ToolPermission getPermission(String name) {
        Tool t = tools.get(name);
        if (t instanceof ToolSpec spec) return spec.getPermission();
        return ToolPermission.CONTROLLED_WRITE;
    }

    /// Returns the tool's source. Tools implementing {@link ToolSpec}
    /// declare their own; others default to {@link ToolSource#LOCAL}.
    public ToolSource getSource(String name) {
        Tool t = tools.get(name);
        if (t instanceof ToolSpec spec) return spec.getSource();
        return ToolSource.LOCAL;
    }

    /// Marks a tool as disabled so it is excluded from {@link #list()}.
    public void disable(String name) {
        disabledTools.add(name);
    }

    /// Re-enables a previously disabled tool.
    public void enable(String name) {
        disabledTools.remove(name);
    }

    /// Returns whether the named tool is currently disabled.
    public boolean isDisabled(String name) {
        return disabledTools.contains(name);
    }

    /// Returns all non-disabled registered tools in insertion order.
    /// The returned list is a snapshot.
    public List<Tool> list() {
        return tools.values().stream()
                .filter(t -> !disabledTools.contains(t.getName()))
                .toList();
    }

    /// Returns all registered tools (including disabled) in insertion order.
    public List<Tool> listAll() {
        return List.copyOf(new ArrayList<>(tools.values()));
    }
}
