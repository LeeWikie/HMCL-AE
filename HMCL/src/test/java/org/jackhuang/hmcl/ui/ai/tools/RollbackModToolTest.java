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

/// Covers [RollbackModTool]'s deterministic contract and guard branches through the real
/// [org.jackhuang.hmcl.addon.mod.ModManager] over a temp mods directory ([ProfileFixture]):
/// the metadata/description contract, the missing-`mod` guard, and the "no archived versions"
/// branch (a mod with no `*.jar.old` backups cannot be rolled back).
///
/// The successful swap ([org.jackhuang.hmcl.addon.mod.ModManager#rollback]) is intentionally NOT
/// exercised here: grouping an active jar with its archived `.old` version relies on the two jars
/// resolving to the SAME mod id, which for real mods comes from in-jar metadata
/// (`fabric.mod.json` / `mods.toml`). Metadata-less placeholder files (all a unit test can cheaply
/// write) fall back to a file-name-derived id, so an active `X.jar` and an archived `X.jar.old`
/// resolve to different ids and never group — mirroring why [UpdateModToolTest] pins the archival
/// state machine directly and leaves the full download/restore round-trip to the manual checklist.
public final class RollbackModToolTest {

    private final RollbackModTool tool = new RollbackModTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("rollback_mod", tool.getName());
        String description = tool.getDescription();
        assertTrue(description.contains(".old"),
                "the description must advertise the .old archive contract: " + description);
        assertTrue(description.toLowerCase(Locale.ROOT).contains("roll"),
                "the description must describe the rollback action: " + description);
    }

    @Test
    void missingModParameterIsRejectedWithoutTouchingAnyProfile() {
        // 'mod' is validated first, before any profile/repository access, so this needs no fixture.
        ToolResult result = tool.execute(Map.of());
        assertFalse(result.isSuccess(), "a call with no 'mod' must fail");
        assertTrue(result.getError().toLowerCase(Locale.ROOT).contains("mod"),
                "the failure must name the missing 'mod' parameter: " + result.getError());
    }

    @Test
    void modWithNoArchivedVersionsIsRejected() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            // A single active mod with no '.old' backups: rollback has nothing to restore to.
            Files.writeString(modsDir.resolve("Sodium.jar"), "sodium-current-bytes");

            ToolResult result = tool.execute(Map.of("instance", "HasMods", "mod", "Sodium"));

            assertFalse(result.isSuccess(),
                    "a mod with no archived versions must be rejected, got: " + result.getOutput());
            assertTrue(result.getError().contains("archived previous versions"),
                    "the failure must explain there is nothing to roll back to: " + result.getError());
        }
    }
}
