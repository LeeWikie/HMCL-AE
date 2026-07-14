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
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [CheckResourcePackUpdatesTool]'s deterministic surface: the metadata/description
/// contract, the `apply`-driven permission grading ([ToolPermission#READ_ONLY] vs.
/// [ToolPermission#CONTROLLED_WRITE], the exact split called out in the class doc), and the
/// instance-resolution guards it shares with [CheckModUpdatesTool] via
/// [InstanceToolSupport#resolveInstance] — all of which run through the real
/// [org.jackhuang.hmcl.game.HMCLGameRepository] / [org.jackhuang.hmcl.addon.resourcepack.ResourcePackManager]
/// over a temp instance directory ([ProfileFixture]).
///
/// Also pinned here: every [ProfileFixture]-created instance deterministically fails at this
/// tool's own Minecraft-version-detection guard, BEFORE it ever calls
/// `ResourcePackManager#getLocalFiles()` — `HMCLGameRepository#getGameVersion` detects the version
/// by parsing `version.json` (or class bytecode) out of the instance's real game jar
/// (`DefaultGameRepository#getVersionJar` / `GameVersion#minecraftVersion`), and [ProfileFixture]
/// (like [ExportModpackToolTest]'s fixture) only ever writes a minimal version manifest, never a
/// real jar. That failure is exercised directly below (with a resource pack file already sitting
/// in the directory, to prove the guard fires before any pack is even looked at) instead of
/// fabricating a fake game jar purely to reach the "no resource packs installed" / "N pack(s) have
/// updates" report bodies, which would test jar-parsing plumbing this tool doesn't own rather than
/// the tool's own logic.
///
/// NOT exercised here (left to the manual checklist, like [CheckModUpdatesToolTest]): the actual
/// update-check ([org.jackhuang.hmcl.addon.resourcepack.ResourcePackFile#checkUpdates]) and
/// apply/download paths, both of which require live Modrinth/CurseForge network calls and real
/// hash-matched remote metadata that cannot be reproduced deterministically in a unit test.
public final class CheckResourcePackUpdatesToolTest {

    private final CheckResourcePackUpdatesTool tool = new CheckResourcePackUpdatesTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("check_resourcepack_updates", tool.getName());
        assertTrue(tool.supportsStructuredSchema());

        String schema = tool.getInputSchemaJson();
        assertTrue(schema.contains("\"apply\""), "schema must declare 'apply': " + schema);
        assertTrue(schema.contains("\"instance\""), "schema must declare 'instance': " + schema);
        assertTrue(schema.contains("\"limit\""), "schema must declare 'limit': " + schema);

        String description = tool.getDescription().toLowerCase(Locale.ROOT);
        assertTrue(description.contains("modrinth") && description.contains("curseforge"),
                "the description must name both remote sources: " + tool.getDescription());
        assertTrue(description.contains("apply"),
                "the description must document the 'apply' parameter: " + tool.getDescription());
        assertTrue(description.contains(".zip"),
                "the description must explain the hash-matched .zip contract: " + tool.getDescription());
        assertTrue(description.contains("read-only"),
                "the description must state the apply=false read-only contract: " + tool.getDescription());
    }

    @Test
    void permissionGradesByApplyFlagAlone() {
        // Default (no-arg) accessor never elevates -- only the map-aware overload grades by 'apply'.
        assertEquals(ToolPermission.READ_ONLY, tool.getPermission());
        assertEquals(ToolPermission.CONTROLLED_WRITE, tool.getMaxPermission());

        assertEquals(ToolPermission.READ_ONLY, tool.getPermission(Map.of()),
                "apply unset must stay read-only");
        assertEquals(ToolPermission.READ_ONLY, tool.getPermission(Map.of("apply", false)),
                "apply=false must stay read-only");
        assertEquals(ToolPermission.READ_ONLY, tool.getPermission(Map.of("apply", "false")),
                "apply='false' (string) must stay read-only");
        assertEquals(ToolPermission.CONTROLLED_WRITE, tool.getPermission(Map.of("apply", true)),
                "apply=true must elevate to CONTROLLED_WRITE, the same class as resourcepacks_install");
        assertEquals(ToolPermission.CONTROLLED_WRITE, tool.getPermission(Map.of("apply", "true")),
                "apply='true' (string) must elevate too, per the tool's own boolean-flag parsing");
    }

    @Test
    void missingNamedInstanceFailsWithCandidateEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");

            ToolResult result = tool.execute(Map.of("instance", "DoesNotExist"));

            assertFalse(result.isSuccess(), "a named-but-missing instance must be rejected");
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
            // No createInstance(): nothing is selected in this fresh profile.
            ToolResult result = tool.execute(Map.of());

            assertFalse(result.isSuccess());
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(result.getError().contains("No instance is selected"),
                    "unexpected message: " + result.getError());
        }
    }

    @Test
    void instanceWithoutARealGameJarFailsVersionDetectionBeforeScanningPacks() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasPacks");
            // A resource pack IS present -- proving the failure below happens before the tool ever
            // reaches ResourcePackManager#getLocalFiles(), not because the pack list is empty.
            Path packsDir = fx.repository().getResourcePackDirectory("HasPacks");
            Files.createDirectories(packsDir);
            Files.writeString(packsDir.resolve("SomePack.zip"), "placeholder-not-a-real-zip");

            ToolResult result = tool.execute(Map.of("instance", "HasPacks"));

            assertFalse(result.isSuccess(),
                    "an instance with no real, parseable game jar cannot have its Minecraft version "
                            + "detected, so the tool must refuse rather than silently report nothing to "
                            + "update; got: " + result.getOutput());
            assertTrue(result.getError().contains("Minecraft version"),
                    "the failure must explain the missing version detection: " + result.getError());
            assertTrue(result.getError().contains("HasPacks"),
                    "the failure must name the instance: " + result.getError());
        }
    }
}
