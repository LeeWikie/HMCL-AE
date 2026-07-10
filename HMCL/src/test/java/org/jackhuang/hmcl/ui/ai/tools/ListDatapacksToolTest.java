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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [ListDatapacksTool]'s "world not found" branch: it now fails with the unified envelope
/// carrying the real world folder names (the [WorldToolSupport] candidate list) instead of a bare
/// sentence. Uses a real [ProfileFixture]-backed instance and worlds on disk (no mocks).
public final class ListDatapacksToolTest {

    private final ListDatapacksTool tool = new ListDatapacksTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("list_datapacks", tool.getName());
        assertTrue(tool.getDescription().toLowerCase().contains("datapack"));
    }

    @Test
    void missingWorldFailsWithCandidateEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path saves = fx.repository().getRunDirectory("Existing").resolve("saves");
            Files.createDirectories(saves.resolve("RealWorldA"));
            Files.createDirectories(saves.resolve("RealWorldB"));

            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "NoSuchWorld"));

            assertFalse(result.isSuccess());
            String err = result.getError();
            assertTrue(ToolFailures.isWellFormedEnvelope(err), "not a well-formed envelope: " + err);
            assertTrue(err.contains("was not found"), err);
            assertTrue(err.contains("RealWorldA") && err.contains("RealWorldB"),
                    "must list the real world names: " + err);
        }
    }
}
