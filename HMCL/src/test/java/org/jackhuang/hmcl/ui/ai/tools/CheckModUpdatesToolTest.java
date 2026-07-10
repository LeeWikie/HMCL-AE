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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// T4: {@link CheckModUpdatesTool} resolves its target through the shared
/// {@link InstanceToolSupport#resolveInstance} range, so both a named-but-missing instance and a
/// no-selection call fail with the unified envelope (candidate list / nothing-selected) — checked
/// before any network round trip.
public final class CheckModUpdatesToolTest {

    private final CheckModUpdatesTool tool = new CheckModUpdatesTool();

    @Test
    void missingNamedInstanceFailsWithCandidateEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");

            ToolResult result = tool.execute(Map.of("instance", "DoesNotExist"));

            assertFalse(result.isSuccess());
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(result.getError().contains("does not exist"), result.getError());
            assertTrue(result.getError().contains("Existing"),
                    "should list the real instance names: " + result.getError());
        }
    }

    @Test
    void noInstanceSelectedFailsWithEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            // No createInstance(): nothing is selected.
            ToolResult result = tool.execute(Map.of());

            assertFalse(result.isSuccess());
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(result.getError().contains("No instance is selected"),
                    "unexpected message: " + result.getError());
        }
    }
}
