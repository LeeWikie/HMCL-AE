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
package org.jackhuang.hmcl.ui.ai;

import org.jackhuang.hmcl.setting.SettingsManager;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/// Backup / restore of HMCL-AE's own data (settings, chat history, skills, memory,
/// search/MCP config) as a single zip under the `.hmcl/` config directory.
///
/// Self-contained (java.util.zip only) so it has no extra dependencies. The set of
/// backed-up entries is the AE-owned files/folders; unrelated launcher data is left
/// untouched. "Slim" mode skips large/regenerable folders (memory images, knowledge).
@NotNullByDefault
public final class AiDataBackup {

    private AiDataBackup() {
    }

    /// AE-owned files (relative to the config dir) included in a backup.
    private static final List<String> DATA_FILES = List.of(
            "ai-settings.json",
            "ai-chat-settings.json",
            "ai-mcp-settings.json",
            "ai-search-settings.json",
            "ai-tool-permissions.json");

    /// AE-owned folders (relative to the config dir) included in a full backup.
    private static final List<String> DATA_DIRS = List.of(
            "ai-skills",
            "ai-memory",
            "ai-sessions");

    /// Folders skipped when {@code slim} is requested (large / regenerable).
    private static final List<String> SLIM_SKIP_DIRS = List.of(
            "ai-memory");

    /// Writes a zip backup of the AE data to {@code target}.
    ///
    /// @param target the destination zip file
    /// @param slim   when true, skips large/regenerable folders
    /// @return the number of files written into the archive
    /// @throws IOException on I/O error
    public static int backup(Path target, boolean slim) throws IOException {
        Path base = SettingsManager.localConfigDirectory();
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        int[] count = {0};
        try (OutputStream os = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(os)) {

            for (String name : DATA_FILES) {
                Path file = base.resolve(name);
                if (Files.isRegularFile(file)) {
                    putFile(zip, name, file);
                    count[0]++;
                }
            }

            for (String dir : DATA_DIRS) {
                if (slim && SLIM_SKIP_DIRS.contains(dir)) {
                    continue;
                }
                Path root = base.resolve(dir);
                if (!Files.isDirectory(root)) {
                    continue;
                }
                List<Path> files = new ArrayList<>();
                try (var stream = Files.walk(root)) {
                    stream.filter(Files::isRegularFile).forEach(files::add);
                }
                for (Path f : files) {
                    String entry = dir + "/" + root.relativize(f).toString().replace('\\', '/');
                    putFile(zip, entry, f);
                    count[0]++;
                }
            }
        }
        return count[0];
    }

    /// Restores AE data from a zip backup into the config directory, overwriting
    /// existing files. Entries are constrained to the AE-owned prefixes for safety
    /// (a malicious or unrelated zip cannot write elsewhere — Zip Slip is rejected).
    ///
    /// @param source the backup zip to restore
    /// @return the number of files restored
    /// @throws IOException on I/O error
    public static int restore(Path source) throws IOException {
        Path base = SettingsManager.localConfigDirectory().toAbsolutePath().normalize();
        Files.createDirectories(base);
        int restored = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(source))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                if (!isAllowedEntry(name)) {
                    continue;
                }
                Path out = base.resolve(name).toAbsolutePath().normalize();
                if (!out.startsWith(base)) {
                    continue; // Zip Slip guard
                }
                if (out.getParent() != null) {
                    Files.createDirectories(out.getParent());
                }
                try (OutputStream os = Files.newOutputStream(out)) {
                    int n;
                    while ((n = zip.read(buffer)) > 0) {
                        os.write(buffer, 0, n);
                    }
                }
                restored++;
            }
        }
        return restored;
    }

    private static boolean isAllowedEntry(String name) {
        if (DATA_FILES.contains(name)) {
            return true;
        }
        for (String dir : DATA_DIRS) {
            if (name.startsWith(dir + "/")) {
                return true;
            }
        }
        return false;
    }

    private static void putFile(ZipOutputStream zip, String entryName, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        Files.copy(file, zip);
        zip.closeEntry();
    }
}
