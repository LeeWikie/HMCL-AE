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
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [ManageDatapackTool]'s action-routing/parameter guards (unrecognized `action`, missing
/// `world`/`datapack`, a non-boolean `enabled`) and its real filesystem branches over a temp
/// `saves/<world>/datapacks/` tree ([ProfileFixture]): world-not-found, no-datapacks-folder,
/// zero-match, ambiguous-match, the zip toggle (enable⇄disable via the `.disabled` suffix), the
/// folder toggle (enable⇄disable via the inner `pack.mcmeta` rename), the "already in the
/// requested state" no-op, the "both variants exist" collision refusal, a `pack.mcmeta`-less
/// folder's "nothing to toggle" refusal, and a permanent delete (`toRecycleBin` supplier fixed to
/// `false`, matching [FileTrash#delete]'s deterministic non-trash branch — no OS recycle bin
/// involved).
///
/// NOT exercised here: deleting to the real OS recycle bin ([FileTrash#trashSupported] /
/// [java.awt.Desktop#moveToTrash] is platform-dependent and would make the test's outcome depend
/// on the machine/CI image it runs on, not on this tool's logic) and a file locked by a running
/// game (routed through [ToggleModTool#fileOperationFailure], already covered by that tool's own
/// tests). Both are left to the manual release checklist, mirroring how [RollbackModToolTest]
/// leaves the metadata-dependent success grouping to the manual checklist.
public final class ManageDatapackToolTest {

    private final ManageDatapackTool tool = new ManageDatapackTool(() -> false);

    // ---------------------------------------------------------------------------------- metadata

    @Test
    void reportsCorrectMetadata() {
        assertEquals("manage_datapack", tool.getName());
        String description = tool.getDescription();
        assertTrue(description.contains("datapacks_toggle"),
                "the description must advertise the toggle action: " + description);
        assertTrue(description.contains("datapacks_remove"),
                "the description must advertise the remove action: " + description);
        assertTrue(description.toLowerCase(Locale.ROOT).contains("recycle bin"),
                "the description must mention the recycle-bin-preferring delete: " + description);
    }

    // -------------------------------------------------------------------------- guards (no fixture)

    @Test
    void unknownActionIsRejectedWithoutTouchingAnyProfile() {
        // No 'action'/'operation'/'mode' at all: execute() must route to the "which operation"
        // failure before ever looking at world/datapack/Profiles.
        ToolResult result = tool.execute(Map.of());
        assertFalse(result.isSuccess(), "a call with no recognizable action must fail");
        assertTrue(result.getError().contains("datapacks_toggle") && result.getError().contains("datapacks_remove"),
                "the failure must name both valid actions: " + result.getError());
    }

    @Test
    void missingWorldParameterIsRejectedWithoutTouchingAnyProfile() {
        // 'world' is validated first inside run(), before any profile/repository access, so this
        // needs no fixture even though 'datapack' is present.
        ToolResult result = tool.execute(Map.of("action", "datapacks_toggle", "datapack", "MyDatapack"));
        assertFalse(result.isSuccess(), "a call with no 'world' must fail");
        assertTrue(result.getError().contains("world"),
                "the failure must name the missing 'world' parameter: " + result.getError());
    }

    @Test
    void missingDatapackParameterIsRejectedWithoutTouchingAnyProfile() {
        // 'datapack' is validated right after 'world', still before any profile/repository access.
        ToolResult result = tool.execute(Map.of("action", "datapacks_remove", "world", "MyWorld"));
        assertFalse(result.isSuccess(), "a call with no 'datapack' must fail");
        assertTrue(result.getError().contains("datapack"),
                "the failure must name the missing 'datapack' parameter: " + result.getError());
    }

    @Test
    void nonBooleanEnabledValueIsRejectedWithoutTouchingAnyProfile() {
        // The 'enabled' parse also happens before Profiles.getSelectedProfile(), so an invalid
        // value fails deterministically without any fixture.
        ToolResult result = tool.execute(Map.of("action", "datapacks_toggle",
                "world", "MyWorld", "datapack", "MyDatapack", "enabled", "maybe"));
        assertFalse(result.isSuccess(), "a non-boolean 'enabled' must fail");
        assertTrue(result.getError().contains("must be a boolean"),
                "the failure must explain 'enabled' needs a boolean: " + result.getError());
    }

    // --------------------------------------------------------------------------- fixture: resolution

    @Test
    void worldNotFoundFailsWithCandidateEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path saves = fx.repository().getRunDirectory("Existing").resolve("saves");
            Files.createDirectories(saves.resolve("RealWorld"));

            ToolResult result = tool.execute(Map.of("instance", "Existing", "action", "datapacks_toggle",
                    "world", "NoSuchWorld", "datapack", "Anything"));

            assertFalse(result.isSuccess());
            String err = result.getError();
            assertTrue(ToolFailures.isWellFormedEnvelope(err), "not a well-formed envelope: " + err);
            assertTrue(err.contains("was not found"), err);
            assertTrue(err.contains("RealWorld"), "must list the real world name: " + err);
        }
    }

    @Test
    void missingDatapacksFolderIsReportedAsNonRetryable() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld");
            Files.createDirectories(worldDir); // world exists, but no datapacks/ subfolder yet

            ToolResult result = tool.execute(Map.of("instance", "Existing", "action", "datapacks_toggle",
                    "world", "MyWorld", "datapack", "Anything"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("has no datapacks folder yet"),
                    "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("Retryable: no"),
                    "a nonexistent datapacks/ folder is a terminal, non-retryable state: " + result.getError());
        }
    }

    @Test
    void zeroMatchFailsWithCandidateList() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path datapacksDir = fx.repository().getRunDirectory("Existing").resolve("saves")
                    .resolve("MyWorld").resolve("datapacks");
            Files.createDirectories(datapacksDir);
            Files.writeString(datapacksDir.resolve("Terralith.zip"), "terralith-bytes");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "action", "datapacks_toggle",
                    "world", "MyWorld", "datapack", "NoSuchPack"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("No datapack matching"), "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("Terralith.zip"),
                    "must list the real datapack name: " + result.getError());
        }
    }

    @Test
    void ambiguousMatchIsRejected() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path datapacksDir = fx.repository().getRunDirectory("Existing").resolve("saves")
                    .resolve("MyWorld").resolve("datapacks");
            Files.createDirectories(datapacksDir);
            Files.writeString(datapacksDir.resolve("Terralith.zip"), "a");
            Files.writeString(datapacksDir.resolve("TerralithAddon.zip"), "b");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "action", "datapacks_toggle",
                    "world", "MyWorld", "datapack", "Terralith"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Ambiguous"), "unexpected message: " + result.getError());
            assertTrue(result.getError().contains("Terralith.zip") && result.getError().contains("TerralithAddon.zip"),
                    "must list both matching entries: " + result.getError());
        }
    }

    // ----------------------------------------------------------------------------- fixture: toggle

    @Test
    void togglesAZipDatapackFromEnabledToDisabled() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path datapacksDir = fx.repository().getRunDirectory("Existing").resolve("saves")
                    .resolve("MyWorld").resolve("datapacks");
            Files.createDirectories(datapacksDir);
            Files.writeString(datapacksDir.resolve("Terralith.zip"), "terralith-bytes");

            // No 'enabled' given: flips the current (enabled) state to disabled.
            ToolResult result = tool.execute(Map.of("instance", "Existing", "action", "datapacks_toggle",
                    "world", "MyWorld", "datapack", "Terralith"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("disabled"), "unexpected message: " + result.getOutput());
            assertFalse(Files.exists(datapacksDir.resolve("Terralith.zip")), "the enabled file must be gone");
            assertTrue(Files.exists(datapacksDir.resolve("Terralith.zip.disabled")),
                    "the zip must be renamed with the .disabled suffix");
        }
    }

    @Test
    void toggleIsANoOpWhenAlreadyInTheRequestedState() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path datapacksDir = fx.repository().getRunDirectory("Existing").resolve("saves")
                    .resolve("MyWorld").resolve("datapacks");
            Files.createDirectories(datapacksDir);
            Files.writeString(datapacksDir.resolve("Terralith.zip"), "terralith-bytes");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "action", "datapacks_toggle",
                    "world", "MyWorld", "datapack", "Terralith", "enabled", true));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("already enabled") && result.getOutput().contains("no change made"),
                    "unexpected message: " + result.getOutput());
            assertTrue(Files.exists(datapacksDir.resolve("Terralith.zip")), "the file must be left untouched");
        }
    }

    @Test
    void togglesAFolderDatapackByRenamingItsInnerPackMcmeta() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path datapacksDir = fx.repository().getRunDirectory("Existing").resolve("saves")
                    .resolve("MyWorld").resolve("datapacks");
            Path packDir = datapacksDir.resolve("VanillaTweaks");
            Files.createDirectories(packDir);
            Files.writeString(packDir.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":15}}");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "action", "datapacks_toggle",
                    "world", "MyWorld", "datapack", "VanillaTweaks", "enabled", false));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("disabled"), "unexpected message: " + result.getOutput());
            assertTrue(Files.isDirectory(packDir), "the folder itself must never be moved/renamed");
            assertFalse(Files.exists(packDir.resolve("pack.mcmeta")), "the active pack.mcmeta must be gone");
            assertTrue(Files.exists(packDir.resolve("pack.mcmeta.disabled")),
                    "the inner pack.mcmeta must be renamed with the .disabled suffix");
        }
    }

    @Test
    void refusesToToggleAFolderWhenBothPackMcmetaVariantsAlreadyExist() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path datapacksDir = fx.repository().getRunDirectory("Existing").resolve("saves")
                    .resolve("MyWorld").resolve("datapacks");
            Path packDir = datapacksDir.resolve("VanillaTweaks");
            Files.createDirectories(packDir);
            // Both the active AND a stray disabled copy already present: disabling would silently
            // clobber the existing pack.mcmeta.disabled.
            Files.writeString(packDir.resolve("pack.mcmeta"), "active");
            Files.writeString(packDir.resolve("pack.mcmeta.disabled"), "stale-disabled-copy");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "action", "datapacks_toggle",
                    "world", "MyWorld", "datapack", "VanillaTweaks"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("already exists"), "unexpected message: " + result.getError());
            assertEquals("active", Files.readString(packDir.resolve("pack.mcmeta")), "must be left untouched");
            assertEquals("stale-disabled-copy", Files.readString(packDir.resolve("pack.mcmeta.disabled")),
                    "must be left untouched");
        }
    }

    @Test
    void folderWithoutPackMcmetaCannotBeToggled() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path datapacksDir = fx.repository().getRunDirectory("Existing").resolve("saves")
                    .resolve("MyWorld").resolve("datapacks");
            Path notAPack = datapacksDir.resolve("NotADatapack");
            Files.createDirectories(notAPack);
            Files.writeString(notAPack.resolve("readme.txt"), "just a stray folder");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "action", "datapacks_toggle",
                    "world", "MyWorld", "datapack", "NotADatapack"));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("no pack.mcmeta"), "unexpected message: " + result.getError());
        }
    }

    // ------------------------------------------------------------------------------ fixture: remove

    @Test
    void removesADatapackPermanentlyWhenRecycleBinDisabled() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path datapacksDir = fx.repository().getRunDirectory("Existing").resolve("saves")
                    .resolve("MyWorld").resolve("datapacks");
            Files.createDirectories(datapacksDir);
            Files.writeString(datapacksDir.resolve("Terralith.zip"), "terralith-bytes");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "action", "datapacks_remove",
                    "world", "MyWorld", "datapack", "Terralith"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("Permanently deleted"),
                    "the tool's own toRecycleBin supplier is fixed to false: " + result.getOutput());
            assertFalse(Files.exists(datapacksDir.resolve("Terralith.zip")), "the file must be gone from disk");
        }
    }
}
