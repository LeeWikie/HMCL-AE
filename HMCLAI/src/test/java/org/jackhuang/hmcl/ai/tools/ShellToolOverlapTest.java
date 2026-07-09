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
package org.jackhuang.hmcl.ai.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Locks in the golden-rule shell/dedicated-tool overlap detector: a download verb landing on a
/// Minecraft content directory or a known mod-hosting host is flagged; a download verb alone, a
/// game path alone, or an unrelated host is not.
public final class ShellToolOverlapTest {

    @Test
    void modrinthCdnDownloadIntoModsIsFlagged() {
        assertNotNull(ShellToolOverlap.overlapReason(
                "Invoke-WebRequest -Uri https://cdn.modrinth.com/data/AANobbMI/versions/1.2.3/sodium.jar"
                        + " -OutFile C:\\Users\\me\\.minecraft\\mods\\sodium.jar"));
        assertNotNull(ShellToolOverlap.overlapReason(
                "iwr https://api.modrinth.com/v2/version/abc/download -OutFile mods\\fabric-api.jar"));
    }

    @Test
    void curseforgeDownloadIntoModsIsFlagged() {
        assertNotNull(ShellToolOverlap.overlapReason(
                "curl -L https://edge.forgecdn.net/files/1234/5678/jei.jar -o mods/jei.jar"));
        assertNotNull(ShellToolOverlap.overlapReason(
                "wget https://media.forgecdn.net/files/1234/5678/optifine.jar -O .minecraft/mods/optifine.jar"));
        assertNotNull(ShellToolOverlap.overlapReason(
                "Invoke-RestMethod -Uri https://api.curseforge.com/v1/mods/1/files/2/download-url -OutFile mods\\x.jar"));
    }

    /// curl/wget variants beyond the two above, and the .NET WebClient / BITS / certutil download
    /// paths a model might reach for on Windows instead of curl/iwr.
    @Test
    void curlAndWgetVariantsAreFlagged() {
        assertNotNull(ShellToolOverlap.overlapReason(
                "curl https://modrinth.com/mod/sodium/version/1.2.3/download -o C:\\instances\\1.20.1\\mods\\sodium.jar"));
        assertNotNull(ShellToolOverlap.overlapReason(
                "(New-Object Net.WebClient).DownloadFile('https://curseforge.com/x', 'saves/backup.zip')"));
        assertNotNull(ShellToolOverlap.overlapReason(
                "Start-BitsTransfer -Source https://media.forgecdn.net/files/1/2/pack.zip -Destination resourcepacks\\pack.zip"));
        assertNotNull(ShellToolOverlap.overlapReason(
                "certutil -urlcache -split -f https://cdn.modrinth.com/data/x/y.jar mods\\y.jar"));
        assertNotNull(ShellToolOverlap.overlapReason(
                "bitsadmin /transfer job https://api.curseforge.com/x shaderpacks\\x.zip"));
    }

    @Test
    void downloadVerbWithoutGamePathOrHostIsNotFlagged() {
        // Fetching something unrelated (a changelog, a random file) is not a dedicated-tool overlap.
        assertNull(ShellToolOverlap.overlapReason("curl https://example.com/changelog.txt -o changelog.txt"));
        assertNull(ShellToolOverlap.overlapReason("Invoke-WebRequest -Uri https://example.com/status -OutFile status.json"));
        assertNull(ShellToolOverlap.overlapReason("wget https://example.org/readme.md"));
    }

    @Test
    void gamePathWithoutDownloadVerbIsNotFlagged() {
        // Listing/reading the game directory is normal shell use, not a download-tool overlap.
        assertNull(ShellToolOverlap.overlapReason("ls C:\\Users\\me\\.minecraft\\mods"));
        assertNull(ShellToolOverlap.overlapReason("Get-ChildItem .minecraft\\saves"));
        assertNull(ShellToolOverlap.overlapReason("echo modrinth.com"));
    }

    @Test
    void unrelatedHostIsNotFlagged() {
        assertNull(ShellToolOverlap.overlapReason("curl https://github.com/foo/bar/releases/download/v1/tool.zip -o tool.zip"));
        assertNull(ShellToolOverlap.overlapReason("Invoke-WebRequest -Uri https://raw.githubusercontent.com/foo/bar/main/readme.md"));
    }

    @Test
    void blankOrNullCommandIsNotFlagged() {
        assertNull(ShellToolOverlap.overlapReason(null));
        assertNull(ShellToolOverlap.overlapReason(""));
        assertNull(ShellToolOverlap.overlapReason("   "));
    }
}
