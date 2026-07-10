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

import org.jackhuang.hmcl.addon.mod.LocalModFile;
import org.jackhuang.hmcl.addon.mod.ModManager;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [UpdateModTool]'s G9 change: the superseded old jar is no longer trashed/deleted but
/// archived through the native state machine ([UpdateModTool#archiveOldJar] →
/// [`LocalModFile#setOld(boolean)`]), producing the `*.jar.old` convention HMCL's native
/// "roll back to previous version" reads — so rollback stays effective after an AI-driven
/// update. The full `execute()` path needs the network (remote hash matching + download), so
/// these tests pin the archival/restore state machine on a real [ModManager] over a temp mods
/// directory; the download orchestration around it is exercised by the manual checklist.
public final class UpdateModToolTest {

    private final UpdateModTool tool = new UpdateModTool(() -> false);

    @Test
    void reportsCorrectMetadata() {
        assertEquals("update_mod", tool.getName());
        assertEquals(ToolPermission.CONTROLLED_WRITE, tool.getPermission());
        assertTrue(tool.supportsStructuredSchema());
        assertTrue(tool.getDescription().contains("archives the old jar"),
                "the description must advertise the .old archive contract: " + tool.getDescription());
        assertTrue(tool.getDescription().contains(".old"),
                "the description must mention the .old suffix: " + tool.getDescription());
    }

    @Test
    void archiveOldJarKeepsOldArchiveForNativeRollback() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            Path jar = modsDir.resolve("Sodium.jar");
            Files.writeString(jar, "old-version-bytes");

            ModManager manager = fx.repository().getModManager("HasMods");
            List<LocalModFile> mods = manager.getLocalFiles();
            assertEquals(1, mods.size());
            LocalModFile mod = mods.get(0);

            String note = UpdateModTool.archiveOldJar(mod);

            assertFalse(Files.exists(jar), "the old jar must vacate its original name");
            Path archived = modsDir.resolve("Sodium.jar.old");
            assertTrue(Files.exists(archived), "the old jar must be kept as a .old archive");
            assertEquals("old-version-bytes", Files.readString(archived), "the archive must keep the content");
            assertTrue(mod.isOld(), "the LocalModFile must be in the old state");
            assertTrue(note.contains(".old"), "the receipt must mention the archive: " + note);
            assertTrue(note.contains("Sodium.jar"), "the receipt must name the old jar: " + note);

            // A fresh ModManager (what the native mod page builds) must NOT list the archive as
            // an active mod — .old files are rollback targets, not duplicates.
            assertTrue(fx.repository().getModManager("HasMods").getLocalFiles().isEmpty(),
                    "the .old archive must not appear as an active mod");
        }
    }

    @Test
    void archiveOldJarNormalizesDisabledJarToSingleOldSuffix() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            Files.writeString(modsDir.resolve("Sodium.jar.disabled"), "disabled-old-version");

            ModManager manager = fx.repository().getModManager("HasMods");
            LocalModFile mod = manager.getLocalFiles().get(0);

            UpdateModTool.archiveOldJar(mod);

            // The native convention strips .disabled before adding .old (never "*.disabled.old").
            assertTrue(Files.exists(modsDir.resolve("Sodium.jar.old")),
                    "a disabled jar must archive to the plain .old name");
            assertFalse(Files.exists(modsDir.resolve("Sodium.jar.disabled")));
            assertFalse(Files.exists(modsDir.resolve("Sodium.jar.disabled.old")));
        }
    }

    @Test
    void archivedJarCanBeRestoredAfterFailedDownload() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("HasMods");
            Path modsDir = fx.repository().getRunDirectory("HasMods").resolve("mods");
            Files.createDirectories(modsDir);
            Path jar = modsDir.resolve("Sodium.jar");
            Files.writeString(jar, "old-version-bytes");

            ModManager manager = fx.repository().getModManager("HasMods");
            LocalModFile mod = manager.getLocalFiles().get(0);

            // Same-name update path: archive first (frees the file name), then a failing download
            // triggers setOld(false) — the exact restore call UpdateModTool.execute performs.
            UpdateModTool.archiveOldJar(mod);
            assertFalse(Files.exists(jar));
            mod.setOld(false);

            assertTrue(Files.exists(jar), "the restore must bring the original name back");
            assertEquals("old-version-bytes", Files.readString(jar));
            assertFalse(Files.exists(modsDir.resolve("Sodium.jar.old")), "the archive name must be vacated");
            assertFalse(mod.isOld());
        }
    }
}
