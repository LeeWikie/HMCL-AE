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
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [DeleteModTool] after its move onto the native [ModManager]/[LocalModFile] state
/// machine (G9): resolution through `getModManager().getLocalFiles()` (none / one / several,
/// enabled and disabled files), the permanent-delete path, and the G10 occupancy attribution —
/// a failed deletion is blamed on the running game (via the [GameResourceGuard] test probe)
/// with the unified failure envelope, or falls back to a generic "file locked" envelope.
/// A non-empty directory named like a jar forces the deletion failure deterministically on
/// every platform (Files.deleteIfExists throws DirectoryNotEmptyException).
public final class DeleteModToolTest {

    private final DeleteModTool tool = new DeleteModTool(() -> false);

    @AfterEach
    void restoreInstanceRunningProbe() {
        GameResourceGuard.setInstanceRunningProbeForTesting(null);
    }

    @Test
    void reportsCorrectMetadata() {
        assertEquals("delete_mod", tool.getName());
        assertEquals(ToolPermission.CONTROLLED_WRITE, tool.getPermission());
        assertTrue(tool.supportsStructuredSchema());
        assertTrue(tool.getDescription().toLowerCase().contains("mod"));
    }

    @Test
    void missingModParameterFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            ToolResult result = tool.execute(Map.of("instance", "Existing"));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("mod"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void missingModsDirectoryFailsWithEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("NoMods");
            ToolResult result = tool.execute(Map.of("instance", "NoMods", "mod", "foo"));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Mods directory does not exist"),
                    "unexpected message: " + result.getError());
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
        }
    }

    @Test
    void noMatchFailsWithEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            Files.writeString(modsDir.resolve("JEI.jar"), "jar-bytes");

            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "NoSuchMod"));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("No mod file matching"), "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("list_mods"), "should point at list_mods: " + result.getError());
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(Files.exists(modsDir.resolve("JEI.jar")), "nothing must be deleted on no-match");
        }
    }

    @Test
    void noMatchListsInstalledFileNamesWithEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            Files.writeString(modsDir.resolve("JEI.jar"), "a");
            Files.writeString(modsDir.resolve("Sodium.jar"), "b");

            // The shared resolveTrackedMod zero-match path must carry the real installed file
            // names here too, and must not delete anything.
            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "NoSuchMod"));
            assertFalse(result.isSuccess());
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(result.getError().contains("Retryable: yes"), "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("installed mods:"),
                    "should carry the installed file list: " + result.getError());
            assertTrue(result.getError().contains("JEI.jar"), "should list JEI.jar: " + result.getError());
            assertTrue(result.getError().contains("Sodium.jar"), "should list Sodium.jar: " + result.getError());
            assertTrue(result.getError().contains("list_mods"), "should point at list_mods: " + result.getError());
            assertTrue(Files.exists(modsDir.resolve("JEI.jar")), "nothing must be deleted on no-match");
            assertTrue(Files.exists(modsDir.resolve("Sodium.jar")), "nothing must be deleted on no-match");
        }
    }

    @Test
    void ambiguousMatchFailsAndDeletesNothing() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            Files.writeString(modsDir.resolve("JEI-forge.jar"), "a");
            Files.writeString(modsDir.resolve("JEI-fabric.jar"), "b");

            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "jei"));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Ambiguous"), "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("JEI-forge.jar"));
            assertTrue(result.getError().contains("JEI-fabric.jar"));
            assertTrue(Files.exists(modsDir.resolve("JEI-forge.jar")));
            assertTrue(Files.exists(modsDir.resolve("JEI-fabric.jar")));
        }
    }

    @Test
    void deletesEnabledModPermanentlyThroughStateMachine() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            Path jar = modsDir.resolve("Sodium.jar");
            Files.writeString(jar, "jar-bytes");

            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "Sodium"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("Permanently deleted"), "unexpected message: " + result.getOutput());
            assertTrue(result.getOutput().contains("enabled"), "unexpected message: " + result.getOutput());
            assertFalse(Files.exists(jar), "the mod file must be gone");
            assertTrue(fx.repository().getModManager("HasMods").getLocalFiles().isEmpty(),
                    "the ModManager must not see the deleted mod any more");
        }
    }

    @Test
    void deletesDisabledModAndReportsItsState() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            Path disabled = modsDir.resolve("Sodium.jar.disabled");
            Files.writeString(disabled, "jar-bytes");

            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "Sodium"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("disabled"), "unexpected message: " + result.getOutput());
            assertFalse(Files.exists(disabled), "the disabled mod file must be gone");
        }
    }

    @Test
    void failedDeletionIsBlamedOnRunningGame() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            // A non-empty DIRECTORY named like a jar: the ModManager tracks it (metadata parse
            // falls back), but Files.deleteIfExists refuses with DirectoryNotEmptyException —
            // a deterministic, platform-independent stand-in for a file held open by the game.
            Path fakeJar = modsDir.resolve("Locked.jar");
            Files.createDirectories(fakeJar);
            Files.writeString(fakeJar.resolve("payload.bin"), "in-use");

            GameResourceGuard.setInstanceRunningProbeForTesting("HasMods"::equals);

            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "Locked"));

            assertFalse(result.isSuccess());
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(result.getError().contains("Retryable: later"), "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("running"), "should blame the running game: " + result.getError());
            assertTrue(result.getError().contains("nothing was changed"),
                    "must state that no change was made: " + result.getError());
            assertTrue(Files.exists(fakeJar.resolve("payload.bin")), "the content must be untouched");
        }
    }

    @Test
    void failedDeletionFallsBackToGenericLockEnvelopeWhenNotRunning() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            Path fakeJar = modsDir.resolve("Locked.jar");
            Files.createDirectories(fakeJar);
            Files.writeString(fakeJar.resolve("payload.bin"), "in-use");

            GameResourceGuard.setInstanceRunningProbeForTesting(id -> false);

            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "Locked"));

            assertFalse(result.isSuccess());
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(result.getError().contains("Retryable: later"), "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("Nothing was changed"),
                    "must state that no change was made: " + result.getError());
        }
    }
}
