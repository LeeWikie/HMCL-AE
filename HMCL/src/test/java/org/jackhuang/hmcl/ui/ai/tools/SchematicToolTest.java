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

import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [SchematicTool]'s deterministic contract and guard branches: the metadata/description
/// contract, the missing-action routing guidance, the `path`/`name`-missing guards of
/// `schematics_import`/`schematics_delete` (which fail before any [org.jackhuang.hmcl.setting.Profiles]
/// access, so they need no fixture), and the real on-disk `schematics_list`/`schematics_delete`
/// branches over a fresh temp instance ([ProfileFixture]): the "folder does not exist yet" empty
/// state, the "folder exists but is empty" empty state, `schematics_delete` against a
/// not-yet-created folder, and `schematics_delete`'s zero-match failure (which enumerates the real
/// on-disk entries).
///
/// What is intentionally NOT exercised here:
///   - A successful `schematics_import`: [org.jackhuang.hmcl.schematic.LitematicFile#load] parses
///     real Litematica NBT bytes before the copy is allowed, and fabricating a minimal-but-valid
///     `.litematic` file is exactly the kind of "real mod/file-format metadata" round-trip this
///     suite leaves to the manual checklist (mirrors why [RollbackModToolTest] does not exercise a
///     real archive-swap either). `schematics_delete`/`schematics_list`'s matching and directory
///     walk, by contrast, only ever look at file NAMES/extensions — not file contents — so those
///     are safely covered with placeholder bytes.
///   - A successful `schematics_delete` swap or `schematics_reveal`: `reveal` calls
///     [org.jackhuang.hmcl.ui.FXUtils#openFolder]/`showFileInExplorer`, which spawn a real OS
///     file-manager process — not something a deterministic, side-effect-free unit test should do.
///   - The ambiguous-match (`matches.size() > 1`) branch of `schematics_delete`/`schematics_reveal`:
///     structurally identical to the zero-match branch already covered below, just a different
///     message template.
public final class SchematicToolTest {

    private final SchematicTool tool = new SchematicTool(() -> false);

    @Test
    void reportsCorrectMetadata() {
        assertEquals("manage_schematic", tool.getName());
        String description = tool.getDescription();
        String lower = description.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("litematic"),
                "the description must name the .litematic file format: " + description);
        assertTrue(lower.contains("schematics_list") && lower.contains("schematics_import")
                        && lower.contains("schematics_delete") && lower.contains("schematics_reveal"),
                "the description must enumerate all four actions: " + description);
    }

    @Test
    void missingActionGivesRoutingGuidanceWithoutTouchingAnyProfile() {
        // No 'action'/'operation'/'mode' at all: routed straight to the default branch before any
        // profile/repository access, so this needs no fixture.
        ToolResult result = tool.execute(Map.of());
        assertFalse(result.isSuccess(), "a call with no action must fail");
        String error = result.getError();
        assertTrue(error.contains("schematics_list") && error.contains("schematics_import")
                        && error.contains("schematics_delete") && error.contains("schematics_reveal"),
                "the guidance must name all four actions: " + error);
    }

    @Test
    void importMissingPathIsRejectedWithoutTouchingAnyProfile() {
        // 'path' is validated first, before any profile/repository access, so this needs no fixture.
        ToolResult result = tool.execute(Map.of("action", "schematics_import"));
        assertFalse(result.isSuccess(), "a schematics_import call with no 'path' must fail");
        assertTrue(result.getError().toLowerCase(Locale.ROOT).contains("path"),
                "the failure must name the missing 'path' parameter: " + result.getError());
    }

    @Test
    void deleteMissingNameIsRejectedWithoutTouchingAnyProfile() {
        // 'name' is validated first, before any profile/repository access, so this needs no fixture.
        ToolResult result = tool.execute(Map.of("action", "schematics_delete"));
        assertFalse(result.isSuccess(), "a schematics_delete call with no 'name' must fail");
        assertTrue(result.getError().toLowerCase(Locale.ROOT).contains("name"),
                "the failure must name the missing 'name' parameter: " + result.getError());
    }

    @Test
    void listOnFreshInstanceReportsMissingSchematicsFolder() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            // No schematics/ folder was ever created for this instance.

            ToolResult result = tool.execute(Map.of("instance", "Inst", "action", "schematics_list"));

            assertTrue(result.isSuccess(),
                    "listing a never-touched instance must succeed with an empty-state message, got: "
                            + result.getError());
            assertTrue(result.getOutput().contains("has no schematics yet"),
                    "the output must report the missing-folder empty state: " + result.getOutput());
            assertTrue(result.getOutput().contains("schematics_import"),
                    "the output must point at schematics_import to add one: " + result.getOutput());
        }
    }

    @Test
    void listOnEmptySchematicsFolderReportsEmpty() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            Path schematicsDir = fx.repository().getRunDirectory("Inst").resolve("schematics");
            Files.createDirectories(schematicsDir);
            // The folder exists but contains no .litematic files or sub-folders.

            ToolResult result = tool.execute(Map.of("instance", "Inst", "action", "schematics_list"));

            assertTrue(result.isSuccess(),
                    "listing an empty schematics folder must succeed with an empty-state message, got: "
                            + result.getError());
            assertTrue(result.getOutput().contains("is empty"),
                    "the output must report the empty-folder state: " + result.getOutput());
        }
    }

    @Test
    void deleteOnFreshInstanceWithNoSchematicsFolderIsRejected() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            // No schematics/ folder exists yet, so there is nothing to delete.

            ToolResult result = tool.execute(Map.of("instance", "Inst", "action", "schematics_delete", "name", "Foo"));

            assertFalse(result.isSuccess(),
                    "deleting from a non-existent schematics folder must fail, got: " + result.getOutput());
            assertTrue(result.getError().contains("no schematics folder"),
                    "the failure must explain the folder does not exist: " + result.getError());
        }
    }

    @Test
    void deleteWithNoMatchEnumeratesRealEntries() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            Path schematicsDir = fx.repository().getRunDirectory("Inst").resolve("schematics");
            Files.createDirectories(schematicsDir);
            // A real on-disk entry that does NOT match the query below; matching/enumeration only
            // look at file names/extensions, never file contents, so placeholder bytes are fine.
            Files.writeString(schematicsDir.resolve("Foo.litematic"), "placeholder-bytes");

            ToolResult result = tool.execute(Map.of("instance", "Inst", "action", "schematics_delete", "name", "Bar"));

            assertFalse(result.isSuccess(),
                    "a name that matches nothing must be rejected, got: " + result.getOutput());
            assertTrue(result.getError().contains("No schematic matching"),
                    "the failure must explain nothing matched: " + result.getError());
            assertTrue(result.getError().contains("Foo.litematic"),
                    "the failure must enumerate the real on-disk entry: " + result.getError());
        }
    }
}
