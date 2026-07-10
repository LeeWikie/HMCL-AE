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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [WorldToolSupport] — the shared "world (save folder) not found" candidate-enumeration
/// helper that the whole world/save tool suite (delete_world / install_datapack / list_datapacks /
/// world backups / NBT save editing) routes its missing-world failures through. Mirrors
/// [InstanceToolSupportTest]'s coverage of the "instance not found" candidate list.
public final class WorldToolSupportTest {

    @Test
    void availableWorldNamesListsWorldDirsSortedSkippingHiddenLeftovers(@TempDir Path saves) throws IOException {
        Files.createDirectories(saves.resolve("Bravo"));
        Files.createDirectories(saves.resolve("alpha"));
        Files.createDirectories(saves.resolve(".Bravo.replaced"));       // interrupted-restore leftover
        Files.createDirectories(saves.resolve(".Bravo.restore-in-progress"));
        Files.writeString(saves.resolve("not-a-world.txt"), "x");        // a file, not a world dir

        String names = WorldToolSupport.availableWorldNames(saves);

        assertTrue(names.contains("alpha"), names);
        assertTrue(names.contains("Bravo"), names);
        assertFalse(names.contains(".Bravo"), "hidden restore leftovers must be skipped: " + names);
        assertFalse(names.contains("not-a-world.txt"), "plain files are not worlds: " + names);
        assertTrue(names.indexOf("alpha") < names.indexOf("Bravo"),
                "world names must be sorted case-insensitively: " + names);
    }

    @Test
    void availableWorldNamesReportsNoneForEmptyOrMissingSaves(@TempDir Path saves) {
        assertTrue(WorldToolSupport.availableWorldNames(saves).contains("none"),
                "an empty saves/ directory must report 'none', not throw");
        assertTrue(WorldToolSupport.availableWorldNames(saves.resolve("does-not-exist")).contains("none"),
                "a missing saves/ directory must degrade to 'none', not throw");
    }

    @Test
    void worldNotFoundEnvelopeIsWellFormedAndCarriesTheRealWorldNames(@TempDir Path saves) throws IOException {
        Files.createDirectories(saves.resolve("KeepThis"));
        Files.createDirectories(saves.resolve("AndThis"));

        String env = WorldToolSupport.worldNotFoundEnvelope(saves, "Typo");

        assertTrue(ToolFailures.isWellFormedEnvelope(env), "not a well-formed envelope: " + env);
        assertTrue(env.contains("'Typo'"), env);
        assertTrue(env.contains("KeepThis"), "must list the real world names: " + env);
        assertTrue(env.contains("AndThis"), "must list the real world names: " + env);
        // "was not found" is retained verbatim so the wording stays consistent with the
        // pre-existing world tools (and the tests that assert on it).
        assertTrue(env.contains("was not found"), env);
    }
}
