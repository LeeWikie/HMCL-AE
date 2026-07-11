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
import java.util.concurrent.ConcurrentHashMap;

/// A registry that stores and looks up AI [`Tool`] instances by name,
/// with optional metadata (permission, source, enabled/disabled).
///
/// Tools are registered with a unique name and can be looked up or listed.
///
/// # Thread-safety
///
/// Fully internally synchronized: the {@link #tools} map and {@link #disabledTools} set are only
/// ever touched while holding {@link #lock}, and {@link #list()} / {@link #listAll()} build their
/// result snapshot inside that lock. This matters because the two collections are mutated from the
/// FX thread mid-turn — the {@code ToggleToolsBinder} hot tool-toggle binders
/// register/unregister as the user flips a setting, and {@code AIMainPage.applyPlanGating}
/// disables/enables tools right before a plan-mode turn — while the worker thread running that
/// turn concurrently iterates {@link #list()} to build the model-visible tool list. Without this
/// lock that race is a {@link java.util.ConcurrentModificationException} that crashes the whole
/// turn. Callers therefore need NO external synchronization.
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

    private static final String MCP_PREFIX = "mcp.";

    /// Guards every access to {@link #tools} and {@link #disabledTools} (see the class-level
    /// thread-safety note). A single monitor is enough: all operations are O(small) and infrequent,
    /// so there is no contention worth splitting into read/write locks for.
    private final Object lock = new Object();

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final java.util.Set<String> disabledTools = new java.util.HashSet<>();

    /// MCP servers that most recently failed to connect (or connected but whose tool discovery
    /// failed): serverId → the model-visible "not connected" envelope to answer with. Concurrent
    /// because {@code McpClientManager} may (auto-)connect off the UI thread while the model thread
    /// reads via {@link #get}. See {@link #markMcpServerFailed}.
    private final Map<String, String> failedMcpServers = new ConcurrentHashMap<>();

    /// Registers a tool. If a tool with the same name exists, it is replaced.
    public void register(Tool tool) {
        synchronized (lock) {
            tools.put(tool.getName(), tool);
        }
    }

    /// Removes a tool from the registry entirely, so it no longer appears in {@link #list()} /
    /// {@link #listAll()} and {@link #get} returns `null` for it — unlike {@link #disable}, the
    /// tool is not merely hidden from the model's tool list but genuinely gone (used for the
    /// hot web-access toggle: switching 联网工具 off must make web_search/web_fetch
    /// undiscoverable, not "discoverable but failing"). No-op if the name is not registered.
    public void unregister(String name) {
        synchronized (lock) {
            tools.remove(name);
        }
    }

    /// Looks up a tool by name.
    ///
    /// If no tool is registered under {@code name} but the name refers to a tool on an MCP server
    /// currently marked failed (see {@link #markMcpServerFailed}), returns a synthetic READ_ONLY
    /// placeholder whose execution yields that server's "not connected" envelope (borrow-list B8 /
    /// rewrite #17) — so the model learns the server is down instead of getting a generic
    /// "tool not found". This placeholder is never in {@link #list}/{@link #listAll} (the model is
    /// never offered it) and never stored; it exists only to answer a call the model makes from a
    /// tool name it remembers from before the server went down.
    @Nullable
    public Tool get(String name) {
        Tool tool;
        synchronized (lock) {
            tool = tools.get(name);
        }
        if (tool != null) {
            return tool;
        }
        return failedMcpPlaceholder(name);
    }

    /// Records that {@code serverId} failed to connect/discover, so a later call to any unregistered
    /// {@code mcp.<serverId>.*} tool resolves (via {@link #get}) to a placeholder that returns
    /// {@code placeholderEnvelope}. Overwrites any prior entry for the same server.
    public void markMcpServerFailed(String serverId, String placeholderEnvelope) {
        failedMcpServers.put(serverId, placeholderEnvelope);
    }

    /// Clears the failed-server marker for {@code serverId} (on a successful (re)connection or a
    /// clean disconnect). No-op if the server was not marked.
    public void clearMcpServerFailed(String serverId) {
        failedMcpServers.remove(serverId);
    }

    /// Returns a synthetic placeholder for a call to an {@code mcp.<serverId>.*} tool whose server
    /// is currently marked failed, or {@code null} otherwise. Kept off the hot path for the common
    /// case (no failed servers, or a non-MCP name) by the two cheap guards up front. Matches by full
    /// {@code mcp.<serverId>.} prefix so a serverId that itself contains dots is handled correctly.
    @Nullable
    private Tool failedMcpPlaceholder(String name) {
        if (failedMcpServers.isEmpty() || !name.startsWith(MCP_PREFIX)) {
            return null;
        }
        for (Map.Entry<String, String> entry : failedMcpServers.entrySet()) {
            String prefix = MCP_PREFIX + entry.getKey() + ".";
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                return new FailedMcpToolPlaceholder(name, entry.getValue());
            }
        }
        return null;
    }

    /// Returns the tool's WORST-CASE permission (see {@link ToolSpec#getMaxPermission()}) for
    /// display purposes (e.g. the settings page's per-tool permission row). Tools implementing
    /// {@link ToolSpec} declare their own; others default to {@link ToolPermission#CONTROLLED_WRITE}.
    /// Deliberately NOT the same as what a specific call resolves to at runtime — see
    /// {@code LangChain4jToolAdapter#resolvePermission} for the action-aware resolution actually
    /// enforced during execution.
    public ToolPermission getPermission(String name) {
        Tool t;
        synchronized (lock) {
            t = tools.get(name);
        }
        if (t instanceof ToolSpec spec) return spec.getMaxPermission();
        return ToolPermission.CONTROLLED_WRITE;
    }

    /// Returns the tool's source. Tools implementing {@link ToolSpec}
    /// declare their own; others default to {@link ToolSource#LOCAL}.
    public ToolSource getSource(String name) {
        Tool t;
        synchronized (lock) {
            t = tools.get(name);
        }
        if (t instanceof ToolSpec spec) return spec.getSource();
        return ToolSource.LOCAL;
    }

    /// Marks a tool as disabled so it is excluded from {@link #list()}.
    public void disable(String name) {
        synchronized (lock) {
            disabledTools.add(name);
        }
    }

    /// Re-enables a previously disabled tool.
    public void enable(String name) {
        synchronized (lock) {
            disabledTools.remove(name);
        }
    }

    /// Returns whether the named tool is currently disabled.
    public boolean isDisabled(String name) {
        synchronized (lock) {
            return disabledTools.contains(name);
        }
    }

    /// Returns all non-disabled registered tools in insertion order.
    /// The returned list is a snapshot, built under {@link #lock} so it is a consistent view even
    /// while another thread is registering/unregistering or (dis/en)abling tools.
    public List<Tool> list() {
        synchronized (lock) {
            List<Tool> snapshot = new ArrayList<>(tools.size());
            for (Tool t : tools.values()) {
                if (!disabledTools.contains(t.getName())) {
                    snapshot.add(t);
                }
            }
            return List.copyOf(snapshot);
        }
    }

    /// Returns all registered tools (including disabled) in insertion order.
    /// The returned list is a snapshot, built under {@link #lock}.
    public List<Tool> listAll() {
        synchronized (lock) {
            return List.copyOf(new ArrayList<>(tools.values()));
        }
    }

    /// Synthetic stand-in returned by {@link #get} for a call to an {@code mcp.<serverId>.*} tool
    /// whose server is marked failed. It is deliberately classified {@link ToolPermission#READ_ONLY}
    /// and {@link ToolSource#LOCAL} so the executor runs it straight through to its failure result
    /// — no confirm dialog, no MCP force-confirm gate — turning the call into the precise
    /// "server not connected" envelope (rewrite #17) rather than the generic "tool not found".
    private static final class FailedMcpToolPlaceholder implements ToolSpec {
        private final String name;
        private final String envelope;

        FailedMcpToolPlaceholder(String name, String envelope) {
            this.name = name;
            this.envelope = envelope;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return envelope;
        }

        @Override
        public ToolResult execute(Map<String, Object> parameters) {
            return ToolResult.failure(envelope);
        }

        @Override
        public ToolPermission getPermission() {
            return ToolPermission.READ_ONLY;
        }

        @Override
        public ToolPermission getMaxPermission() {
            return ToolPermission.READ_ONLY;
        }

        @Override
        public ToolSource getSource() {
            return ToolSource.LOCAL;
        }
    }
}
