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

    /// Regression test for a legitimate call: a normal, single-segment world name must still list
    /// the real datapacks folder contents (guards against the path-confinement fix below being
    /// overly strict and rejecting valid usage too).
    @Test
    void listsTheDatapacksOfAnExistingWorld() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld");
            Path datapacksDir = worldDir.resolve("datapacks");
            Files.createDirectories(datapacksDir);
            Files.writeString(datapacksDir.resolve("cool-pack.zip"), "zip-bytes");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "MyWorld"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("cool-pack.zip"),
                    "unexpected output: " + result.getOutput());
        }
    }

    /// Regression test for a path-traversal / information-disclosure vulnerability: since
    /// list_datapacks is READ_ONLY and runs silently without a confirmation prompt, a `world`
    /// value crafted with `..` segments must never let the tool enumerate the contents of a
    /// directory outside the instance's saves/ tree.
    @Test
    void pathTraversalWithDotDotSegmentsIsRefused() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path savesDir = fx.repository().getRunDirectory("Existing").resolve("saves");
            Path outsideDatapacks = fx.baseDir().resolve("outside-secret").resolve("datapacks");
            Files.createDirectories(outsideDatapacks);
            Files.writeString(outsideDatapacks.resolve("super-secret-marker.txt"), "leaked");
            String traversal = savesDir.relativize(outsideDatapacks.getParent()).toString();

            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", traversal));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("outside the saves directory"),
                    "unexpected message: " + result.getError());
            assertFalse(result.getError().contains("super-secret-marker"),
                    "must not leak the outside directory's contents: " + result.getError());
        }
    }

    /// Same vulnerability, but via an absolute path — `Path#resolve` treats an absolute argument
    /// as a full replacement of the base path, which is exactly how the escape worked before the
    /// confinement check was added.
    @Test
    void pathTraversalWithAbsolutePathIsRefused() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path outsideDir = fx.baseDir().resolve("outside-secret-abs");
            Path outsideDatapacks = outsideDir.resolve("datapacks");
            Files.createDirectories(outsideDatapacks);
            Files.writeString(outsideDatapacks.resolve("super-secret-marker.txt"), "leaked");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", outsideDir.toString()));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("outside the saves directory"),
                    "unexpected message: " + result.getError());
            assertFalse(result.getError().contains("super-secret-marker"),
                    "must not leak the outside directory's contents: " + result.getError());
        }
    }
}
