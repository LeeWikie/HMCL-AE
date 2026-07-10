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
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Mirrors {@link DeleteModTool}'s leaf-tool contract: {@link ToolPermission#CONTROLLED_WRITE} at
/// this leaf level (the merged `instance` facade elevates the `shaders_delete` action to
/// {@link ToolPermission#DANGEROUS_WRITE} — see {@code InstanceTool#getPermission(java.util.Map)}
/// and {@code CriticalOperationsTest}), a structured schema requiring `shader`, and a hard failure
/// — before ever touching {@code Profiles}/repository state — when `shader` is missing or blank.
///
/// Also covers the two collected-under-{@link DeleteModTool}-parity fixes: the zero-match failure
/// now lists the real installed pack names through the unified {@link ToolFailures} envelope, and a
/// failed deletion is attributed to a running game via the shared
/// {@link ToggleModTool#fileOperationFailure} helper (the {@link GameResourceGuard} test probe).
public final class DeleteShaderToolTest {

    private final DeleteShaderTool tool = new DeleteShaderTool(() -> false);

    @AfterEach
    void restoreInstanceRunningProbe() {
        GameResourceGuard.setInstanceRunningProbeForTesting(null);
    }

    @Test
    void reportsCorrectMetadata() {
        assertEquals("delete_shader", tool.getName());
        assertEquals(ToolPermission.CONTROLLED_WRITE, tool.getPermission());
        assertTrue(tool.supportsStructuredSchema());
        assertTrue(tool.getInputSchemaJson().contains("\"shader\""));
    }

    @Test
    void failsFastWhenShaderParameterIsMissing() {
        ToolResult result = tool.execute(new HashMap<>());
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("shader"));
    }

    @Test
    void failsFastWhenShaderParameterIsBlank() {
        Map<String, Object> params = new HashMap<>();
        params.put("shader", "   ");
        ToolResult result = tool.execute(params);
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("shader"));
    }

    @Test
    void noMatchListsInstalledPackNamesWithEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasShaders");
            Path shadersDir = fx.repository().getRunDirectory("HasShaders").resolve("shaderpacks");
            Files.createDirectories(shadersDir);
            Files.writeString(shadersDir.resolve("BSL.zip"), "a");
            Files.createDirectories(shadersDir.resolve("ComplementaryUnbound"));

            ToolResult result = tool.execute(Map.of("instance", "HasShaders", "shader", "NoSuchShader"));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("No shader pack matching"),
                    "unexpected message: " + result.getError());
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(result.getError().contains("Retryable: yes"), "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("installed shaders:"),
                    "should carry the installed pack list: " + result.getError());
            assertTrue(result.getError().contains("BSL.zip"), "should list the zip pack: " + result.getError());
            assertTrue(result.getError().contains("ComplementaryUnbound"),
                    "should list the folder pack: " + result.getError());
            assertTrue(result.getError().contains("packs_list_local"),
                    "should point at the local list action: " + result.getError());
            assertTrue(Files.exists(shadersDir.resolve("BSL.zip")), "nothing must be deleted on no-match");
            assertTrue(Files.isDirectory(shadersDir.resolve("ComplementaryUnbound")),
                    "nothing must be deleted on no-match");
        }
    }

    @Test
    void noMatchOnEmptyFolderIsWellFormedEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasShaders");
            Path shadersDir = fx.repository().getRunDirectory("HasShaders").resolve("shaderpacks");
            Files.createDirectories(shadersDir);

            ToolResult result = tool.execute(Map.of("instance", "HasShaders", "shader", "anything"));
            assertFalse(result.isSuccess());
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(result.getError().contains("none"), "should say the folder is empty: " + result.getError());
        }
    }

    @Test
    void failedDeletionIsBlamedOnRunningGame() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Playing");
            Path shadersDir = fx.repository().getRunDirectory("Playing").resolve("shaderpacks");
            Files.createDirectories(shadersDir);
            Path pack = shadersDir.resolve("LockedShader.zip");
            Files.writeString(pack, "held-open");
            // Force a deterministic delete failure: a read-only file blocks deletion on Windows,
            // and a non-writable parent directory blocks it on POSIX. This stands in for a file the
            // running game holds open. (If some platform still allows the delete the test skips via
            // the assumption below rather than reporting a false failure.)
            forceUndeletable(shadersDir, pack);
            try {
                GameResourceGuard.setInstanceRunningProbeForTesting("Playing"::equals);

                ToolResult result = tool.execute(Map.of("instance", "Playing", "shader", "LockedShader"));
                Assumptions.assumeTrue(!result.isSuccess(),
                        "platform allowed the delete; occupancy attribution not exercised here");

                assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                        "not a well-formed envelope: " + result.getError());
                assertTrue(result.getError().contains("Retryable: later"), "unexpected message: " + result.getError());
                assertTrue(result.getError().contains("running"),
                        "should blame the running game: " + result.getError());
                assertTrue(result.getError().contains("nothing was changed"),
                        "must state that no change was made: " + result.getError());
                assertTrue(Files.exists(pack), "the pack must be untouched");
            } finally {
                restoreDeletable(shadersDir, pack);
            }
        }
    }

    @Test
    void failedDeletionFallsBackToGenericLockEnvelopeWhenNotRunning() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Idle");
            Path shadersDir = fx.repository().getRunDirectory("Idle").resolve("shaderpacks");
            Files.createDirectories(shadersDir);
            Path pack = shadersDir.resolve("LockedShader.zip");
            Files.writeString(pack, "held-open");
            forceUndeletable(shadersDir, pack);
            try {
                GameResourceGuard.setInstanceRunningProbeForTesting(id -> false);

                ToolResult result = tool.execute(Map.of("instance", "Idle", "shader", "LockedShader"));
                Assumptions.assumeTrue(!result.isSuccess(),
                        "platform allowed the delete; occupancy attribution not exercised here");

                assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                        "not a well-formed envelope: " + result.getError());
                assertTrue(result.getError().contains("Retryable: later"), "unexpected message: " + result.getError());
                assertTrue(result.getError().contains("Nothing was changed"),
                        "must state that no change was made: " + result.getError());
            } finally {
                restoreDeletable(shadersDir, pack);
            }
        }
    }

    /// Makes {@code file} refuse deletion on every major platform: the read-only file attribute
    /// blocks {@code Files.delete} on Windows, and stripping write permission from the containing
    /// directory blocks it on POSIX (deletion is governed by the parent directory there).
    private static void forceUndeletable(Path dir, Path file) {
        file.toFile().setReadOnly();
        dir.toFile().setWritable(false, false);
    }

    /// Reverses {@link #forceUndeletable} so the fixture's best-effort cleanup can remove the temp
    /// tree.
    private static void restoreDeletable(Path dir, Path file) {
        dir.toFile().setWritable(true, false);
        file.toFile().setWritable(true);
    }
}
