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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [InstallLocalContentTool]'s deterministic contract and guard branches: the
/// metadata/description contract, the parameter guards that run before any profile/repository
/// access (missing `kind`, missing `path`, an unrecognised `kind` value), and — through a real
/// [ProfileFixture]-backed instance — the two branches that only depend on file-NAME-based
/// validation rather than parsing real archive content: the source-does-not-exist guard, the
/// `kind`-vs-extension type-mismatch guard, and the self-copy ("already installed") no-op.
///
/// A REAL successful install ([org.jackhuang.hmcl.addon.mod.ModManager#addMod],
/// [org.jackhuang.hmcl.addon.resourcepack.ResourcePackManager#importResourcePack], or the shader
/// folder copy) is intentionally NOT exercised here. The tool's `mod`/`resourcepack` guards
/// ([org.jackhuang.hmcl.addon.mod.ModManager#isFileNameMod],
/// [org.jackhuang.hmcl.addon.resourcepack.ResourcePackFile#isFileResourcePack]) only look at the
/// file NAME/extension (and, for a resource pack folder, whether a `pack.mcmeta` file exists at
/// the top level) — so a placeholder file with the right extension is enough to reach the copy
/// call, but the copy call itself then tries to actually PARSE the file as a mod/pack (jar
/// metadata, zip structure), which a cheaply-written placeholder file does not satisfy and would
/// make the "success" path assert on ModManager/ResourcePackManager parsing behaviour this test
/// isn't set up to pin — mirroring why [RollbackModToolTest] and [UpdateModToolTest] stop short of
/// the real archival/parse round-trip and leave it to the manual checklist. The self-copy branch
/// tested below is the one success-shaped path that returns BEFORE any such parsing happens (it
/// short-circuits on the source and destination already being the same file), so it is safe to
/// assert on precisely.
public final class InstallLocalContentToolTest {

    private final InstallLocalContentTool tool = new InstallLocalContentTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("install_local_content", tool.getName());
        String description = tool.getDescription();
        assertTrue(description.contains("kind"), "must document the 'kind' parameter: " + description);
        assertTrue(description.contains("path"), "must document the 'path' parameter: " + description);
        assertTrue(description.contains("mods_install"),
                "must point the download case at mods_install instead, drawing the local-vs-download line: "
                        + description);
        assertTrue(description.contains("WRITES a new file to disk"),
                "must disclose the write side effect: " + description);
    }

    @Test
    void missingKindParameterIsRejectedWithoutTouchingAnyProfile() {
        // 'kind' is validated first, before 'path' or any profile/repository access.
        ToolResult result = tool.execute(Map.of());
        assertFalse(result.isSuccess(), "a call with no 'kind' must fail");
        assertTrue(result.getError().contains("kind"),
                "the failure must name the missing 'kind' parameter: " + result.getError());
    }

    @Test
    void unknownKindValueIsRejectedWithoutTouchingAnyProfile() {
        ToolResult result = tool.execute(Map.of("kind", "texturepack", "path", "/whatever/file.zip"));
        assertFalse(result.isSuccess(), "an unrecognised 'kind' must fail");
        assertTrue(result.getError().contains("kind"),
                "the failure must name the offending 'kind' parameter: " + result.getError());
        assertTrue(result.getError().contains("texturepack"),
                "the failure must echo back the unrecognised value: " + result.getError());
    }

    @Test
    void missingPathParameterIsRejectedWithoutTouchingAnyProfile() {
        // 'kind' resolves fine here, but 'path' (and its 'file'/'query' aliases) is still
        // validated before any profile/repository access.
        ToolResult result = tool.execute(Map.of("kind", "mod"));
        assertFalse(result.isSuccess(), "a call with no 'path' must fail");
        assertTrue(result.getError().contains("path"),
                "the failure must name the missing 'path' parameter: " + result.getError());
    }

    @Test
    void sourceFileThatDoesNotExistIsRejected() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasInstance");
            Path missing = fx.baseDir().resolve("no-such-mod.jar");

            ToolResult result = tool.execute(
                    Map.of("instance", "HasInstance", "kind", "mod", "path", missing.toString()));

            assertFalse(result.isSuccess(), "a source path that does not exist must be rejected");
            assertTrue(result.getError().contains("does not exist"),
                    "the failure must explain the source file is missing: " + result.getError());
        }
    }

    @Test
    void fileWithWrongExtensionForKindIsRejectedAsTypeMismatch() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasInstance");
            // isFileNameMod() only inspects the name/extension, so a plain '.txt' placeholder is
            // enough to exercise the type-mismatch guard without needing real jar bytes.
            Path notAMod = fx.baseDir().resolve("not-a-mod.txt");
            Files.writeString(notAMod, "just some text, not a jar");

            ToolResult result = tool.execute(
                    Map.of("instance", "HasInstance", "kind", "mod", "path", notAMod.toString()));

            assertFalse(result.isSuccess(), "a '.txt' file must be rejected for kind=mod");
            assertTrue(result.getError().contains("is not a valid mod"),
                    "the failure must call out the type mismatch: " + result.getError());
            assertTrue(result.getError().contains(".jar"),
                    "the failure must name the accepted extension: " + result.getError());
        }
    }

    @Test
    void sourceAlreadyAtTheDestinationIsReportedAsNoOpWithoutCallingModManager() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasInstance");
            Path modsDir = fx.repository().getModsDirectory("HasInstance");
            Files.createDirectories(modsDir);
            // Already sitting in the destination mods folder: the self-copy guard
            // (source == resolved destination) must short-circuit BEFORE ModManager#addMod ever
            // tries to parse this placeholder's (non-real) jar content.
            Path alreadyInstalled = modsDir.resolve("Test.jar");
            Files.writeString(alreadyInstalled, "placeholder-jar-bytes");

            ToolResult result = tool.execute(
                    Map.of("instance", "HasInstance", "kind", "mod", "path", alreadyInstalled.toString()));

            assertTrue(result.isSuccess(),
                    "a source that is already the destination file must succeed as a no-op: " + result.getError());
            assertTrue(result.getOutput().contains("already"),
                    "the output must say the mod is already installed: " + result.getOutput());
            assertTrue(result.getOutput().contains("Nothing to copy."),
                    "the output must make clear nothing was copied: " + result.getOutput());
        }
    }
}
