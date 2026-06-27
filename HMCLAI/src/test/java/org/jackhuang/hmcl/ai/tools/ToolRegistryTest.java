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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for [`ToolRegistry`] covering registration, lookup, and listing.
public final class ToolRegistryTest {

    /// A minimal stub [`Tool`] for verifying registry behaviour.
    private static final class StubTool implements Tool {
        private final String name;
        private final String description;

        StubTool(String name, String description) {
            this.name = name;
            this.description = description;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public ToolResult execute(Map<String, Object> parameters) {
            return ToolResult.success("ok");
        }
    }

    /// Register a tool and retrieve it by name.
    @Test
    public void testRegisterAndGet() {
        ToolRegistry registry = new ToolRegistry();
        Tool tool = new StubTool("test_tool", "A test tool");
        registry.register(tool);

        assertSame(tool, registry.get("test_tool"),
                "get() should return the registered tool");
    }

    /// Listing tools returns them in insertion order.
    @Test
    public void testListPreservesInsertionOrder() {
        ToolRegistry registry = new ToolRegistry();
        Tool a = new StubTool("alpha", "First");
        Tool b = new StubTool("beta", "Second");
        registry.register(a);
        registry.register(b);

        List<Tool> list = registry.list();
        assertEquals(2, list.size(), "should contain both tools");
        assertSame(a, list.get(0), "first registered tool should be first");
        assertSame(b, list.get(1), "second registered tool should be second");
    }

    /// Looking up an unknown name returns `null`.
    @Test
    public void testGetUnknownNameReturnsNull() {
        ToolRegistry registry = new ToolRegistry();
        assertNull(registry.get("nonexistent"),
                "get() should return null for unknown name");
    }

    /// Registering a tool with the same name replaces the previous entry.
    @Test
    public void testRegisterReplacesExisting() {
        ToolRegistry registry = new ToolRegistry();
        Tool first = new StubTool("shared_name", "First");
        Tool second = new StubTool("shared_name", "Second");
        registry.register(first);
        registry.register(second);

        assertSame(second, registry.get("shared_name"),
                "register() should replace the existing tool with the same name");
        assertEquals(1, registry.list().size(),
                "list() should contain exactly one entry after replacement");
    }

    /// An empty registry returns an empty list.
    @Test
    public void testListEmptyRegistry() {
        ToolRegistry registry = new ToolRegistry();
        assertTrue(registry.list().isEmpty(),
                "list() should be empty for a fresh registry");
    }
}
