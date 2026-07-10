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

import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [StopInstanceTool]'s named-instance validation branch. A `stop_instance` call naming a
/// non-existent instance now reuses the shared "instance does not exist" envelope carrying the real
/// instance names (the same [InstanceToolSupport#instanceNotFoundFailure] the delete/rename tools
/// use), instead of a bare dead-end message — driven against a real [ProfileFixture]-backed profile.
public final class StopInstanceToolTest {

    @Test
    void nonexistentInstanceFailsWithEnvelopeListingRealNames() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            StopInstanceTool tool = new StopInstanceTool();

            ToolResult result = tool.execute(Map.of("instance", "DoesNotExist"));

            assertFalse(result.isSuccess());
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(result.getError().contains("does not exist"),
                    "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("Existing"),
                    "the failure should list the real instance names (candidate list): " + result.getError());
        }
    }

    @Test
    void toolMetadataIsSensible() {
        StopInstanceTool tool = new StopInstanceTool();
        assertEquals("stop_instance", tool.getName());
        assertTrue(tool.getDescription().toLowerCase().contains("stop"),
                "description should mention stopping: " + tool.getDescription());
    }
}
