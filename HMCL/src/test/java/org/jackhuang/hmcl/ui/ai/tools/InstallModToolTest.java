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

/// T20/T4: {@link InstallModTool} resolves its destination instance LIVE (never from the
/// turn-start cached game directory) and does so BEFORE the network mod-resolution round trip, so a
/// bad or ambiguous target fails fast with the shared resolveInstance envelope. Only the pre-network
/// failure branches are exercised here (the happy path downloads a real file over the network).
public final class InstallModToolTest {

    @Test
    void namedMissingInstanceFailsFastWithCandidateEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            // gameDirectory is only the last-resort fallback and must NOT be used here.
            InstallModTool tool = new InstallModTool(fx.baseDir());

            ToolResult result = tool.execute(Map.of("id", "some-mod", "instance", "DoesNotExist"));

            assertFalse(result.isSuccess());
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(result.getError().contains("does not exist"), result.getError());
            assertTrue(result.getError().contains("Existing"),
                    "should list the real instance names (fresh query, not a cached dir): " + result.getError());
        }
    }

    @Test
    void noInstanceSelectedFailsFastWithEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            // Nothing selected: the default target cannot be resolved live, so it must fail rather
            // than silently install into the cached gameDirectory (ST-1).
            InstallModTool tool = new InstallModTool(fx.baseDir());

            ToolResult result = tool.execute(Map.of("id", "some-mod"));

            assertFalse(result.isSuccess());
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(result.getError().contains("No instance is selected"),
                    "unexpected message: " + result.getError());
        }
    }
}
