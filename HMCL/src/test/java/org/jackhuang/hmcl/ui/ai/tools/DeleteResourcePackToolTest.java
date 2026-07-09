/*
 * Hello Minecraft! Launcher - Agent Experience
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
package org.jackhuang.hmcl.ui.ai.tools;

import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Mirrors {@link DeleteModTool}'s leaf-tool contract: {@link ToolPermission#CONTROLLED_WRITE} at
/// this leaf level (the merged `instance` facade elevates the `resourcepacks_delete` action to
/// {@link ToolPermission#DANGEROUS_WRITE} — see {@code InstanceTool#getPermission(java.util.Map)}
/// and {@code CriticalOperationsTest}), a structured schema requiring `pack`, and a hard failure —
/// before ever touching {@code Profiles}/repository state — when `pack` is missing or blank.
public final class DeleteResourcePackToolTest {

    private final DeleteResourcePackTool tool = new DeleteResourcePackTool(() -> false);

    @Test
    void reportsCorrectMetadata() {
        assertEquals("delete_resourcepack", tool.getName());
        assertEquals(ToolPermission.CONTROLLED_WRITE, tool.getPermission());
        assertTrue(tool.supportsStructuredSchema());
        assertTrue(tool.getInputSchemaJson().contains("\"pack\""));
    }

    @Test
    void failsFastWhenPackParameterIsMissing() {
        ToolResult result = tool.execute(new HashMap<>());
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("pack"));
    }

    @Test
    void failsFastWhenPackParameterIsBlank() {
        Map<String, Object> params = new HashMap<>();
        params.put("pack", "   ");
        ToolResult result = tool.execute(params);
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("pack"));
    }
}
