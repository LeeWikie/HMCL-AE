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

/// T4: {@link EditInstanceTool}'s rename existence check now delegates to the shared
/// {@link InstanceToolSupport#instanceNotFoundFailure} range, so a missing instance fails with the
/// unified envelope carrying the real instance names instead of a bare "No such instance".
public final class EditInstanceToolTest {

    private final EditInstanceTool tool = new EditInstanceTool();

    @Test
    void renameMissingInstanceFailsWithCandidateEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");

            ToolResult result = tool.execute(Map.of("action", "rename",
                    "instance", "DoesNotExist", "newName", "Whatever"));

            assertFalse(result.isSuccess());
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(result.getError().contains("does not exist"), result.getError());
            assertTrue(result.getError().contains("Existing"),
                    "the failure should list the real instance names (candidate list): " + result.getError());
        }
    }

    @Test
    void renameExistingInstanceStillReportsNoOpWhenNameUnchanged() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");

            ToolResult result = tool.execute(Map.of("action", "rename",
                    "instance", "Existing", "newName", "Existing"));

            // Existence check passes, so we reach the "already has that name" success branch —
            // confirms the resolveInstance-range swap did not change the happy path.
            assertTrue(result.isSuccess(), "expected success: " + result.getError());
        }
    }
}
