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

import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// A tool that imports a single-player world from a local `.zip` archive by extracting
/// it into the instance's `saves/` directory, with Zip-Slip protection.
///
/// This reuses HMCL's launcher APIs directly:
/// - [`Profiles#getSelectedProfile()`] / [`Profiles#getSelectedInstance()`] for the active profile/instance,
/// - [`HMCLGameRepository#hasVersion(String)`] to validate the instance,
/// - [`HMCLGameRepository#getRunDirectory(String)`] for the isolation-aware run directory,
///   from which `saves` is resolved.
///
/// Extraction uses {@link java.util.zip.ZipFile}. Two archive layouts are supported:
/// - the archive's entries share a single top-level folder (e.g. `MyWorld/level.dat`):
///   that folder becomes the world folder under `saves/`;
/// - the archive has `level.dat` (and friends) at its root: a world folder named after the
///   zip file (without extension) is created under `saves/`.
///
/// Every entry's resolved path is verified to stay inside the target directory (Zip-Slip
/// protection); any entry that escapes aborts the import.
///
/// Permission level: it READS the local zip and WRITES a new world directory under `saves/`.
/// It refuses to overwrite an existing world folder.
@NotNullByDefault
public final class ImportWorldTool implements Tool {

    @Override
    public String getName() {
        return "import_world";
    }

    @Override
    public String getDescription() {
        return "Imports a single-player world from a local .zip archive by extracting it into the instance's saves/ folder. "
                + "Parameters: zip (required, the absolute local path of the .zip world archive), "
                + "instance (optional, the instance id; defaults to the currently selected instance). "
                + "Handles archives that wrap the world in a top-level folder as well as archives with level.dat at the "
                + "root (a world folder named after the zip is then created). Uses java.util.zip with Zip-Slip protection. "
                + "Writes a new world directory under saves/; it refuses to overwrite an existing world folder.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object zipObj = parameters.get("zip");
        if (!(zipObj instanceof String) || ((String) zipObj).trim().isEmpty()) {
            return ToolResult.failure("Parameter 'zip' (the local path of the .zip world archive) is required.");
        }
        String zipText = ((String) zipObj).trim();

        Path zipPath;
        try {
            zipPath = Paths.get(zipText);
        } catch (Throwable e) {
            return ToolResult.failure("Invalid 'zip' path '" + zipText + "': " + e.getMessage());
        }
        if (!Files.isRegularFile(zipPath)) {
            return ToolResult.failure("Zip archive was not found (or is not a regular file): " + zipPath);
        }
        String zipFileName = zipPath.getFileName().toString();
        if (!zipFileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return ToolResult.failure("World archive must be a .zip file, but got: " + zipFileName);
        }

        Profile profile;
        try {
            profile = Profiles.getSelectedProfile();
        } catch (Throwable e) {
            return ToolResult.failure("No profile is currently selected: " + e.getMessage());
        }
        HMCLGameRepository repository = profile.getRepository();

        Object instanceObj = parameters.get("instance");
        String instance;
        if (instanceObj instanceof String && !((String) instanceObj).trim().isEmpty()) {
            instance = ((String) instanceObj).trim();
        } else {
            @Nullable String selected = Profiles.getSelectedInstance();
            if (selected == null) {
                return ToolResult.failure("No instance is selected and no 'instance' parameter was given.");
            }
            instance = selected;
        }

        try {
            if (!repository.hasVersion(instance)) {
                return ToolResult.failure("Instance '" + instance + "' does not exist in the selected profile.");
            }
        } catch (Throwable e) {
            return ToolResult.failure("Failed to verify instance '" + instance + "': " + e.getMessage());
        }

        Path savesDir;
        try {
            savesDir = repository.getRunDirectory(instance).resolve("saves");
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve the run directory of '" + instance + "': " + e.getMessage());
        }

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            // Determine the archive layout: whether all entries share one top-level segment.
            Set<String> topLevel = new LinkedHashSet<>();
            boolean hasRootLevelDat = false;
            boolean anyEntry = false;
            Enumeration<? extends ZipEntry> scan = zip.entries();
            while (scan.hasMoreElements()) {
                ZipEntry entry = scan.nextElement();
                anyEntry = true;
                String name = normalizeName(entry.getName());
                if (name.isEmpty()) {
                    continue;
                }
                int slash = name.indexOf('/');
                topLevel.add(slash >= 0 ? name.substring(0, slash) : name);
                if (slash < 0 && name.equalsIgnoreCase("level.dat")) {
                    hasRootLevelDat = true;
                }
            }
            if (!anyEntry) {
                return ToolResult.failure("The zip archive is empty: " + zipPath);
            }

            // worldFolderName + the directory under which entry paths are resolved.
            String worldFolderName;
            Path extractionRoot;
            if (topLevel.size() == 1 && !hasRootLevelDat) {
                // Archive wraps the world in a single top-level folder; entries already
                // carry that folder as a prefix, so extract relative to saves/ directly.
                worldFolderName = topLevel.iterator().next();
                extractionRoot = savesDir;
            } else {
                // level.dat at root (or mixed): create a folder named after the zip.
                worldFolderName = stripZipExtension(zipFileName);
                extractionRoot = savesDir.resolve(worldFolderName);
            }

            Path worldDir = savesDir.resolve(worldFolderName).normalize();
            // Guard: worldDir must stay directly inside savesDir.
            Path savesNorm = savesDir.normalize();
            if (!worldDir.startsWith(savesNorm) || worldDir.equals(savesNorm)) {
                return ToolResult.failure("Refusing to import: resolved world folder escapes the saves directory.");
            }
            if (Files.exists(worldDir)) {
                return ToolResult.failure("A world folder named '" + worldFolderName + "' already exists: " + worldDir
                        + ". Remove or rename it first.");
            }

            Path rootNorm = extractionRoot.normalize();
            Files.createDirectories(extractionRoot);

            long files = 0;
            long dirs = 0;
            Enumeration<? extends ZipEntry> extract = zip.entries();
            while (extract.hasMoreElements()) {
                ZipEntry entry = extract.nextElement();
                String name = normalizeName(entry.getName());
                if (name.isEmpty()) {
                    continue;
                }

                Path target = rootNorm.resolve(name).normalize();
                // Zip-Slip protection: the resolved path must remain inside the extraction root.
                if (!target.startsWith(rootNorm)) {
                    return ToolResult.failure("Refusing to import: zip entry '" + entry.getName()
                            + "' escapes the target directory (possible Zip-Slip attack). Import aborted.");
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    dirs++;
                } else {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    files++;
                }
            }

            String hint = Files.isRegularFile(worldDir.resolve("level.dat"))
                    ? ""
                    : "\nWarning: no level.dat was found in the imported folder; this may not be a valid Minecraft world.";

            return ToolResult.success("Imported world from '" + zipFileName + "' into instance '" + instance + "'.\n"
                    + "World folder: " + worldDir + "\n"
                    + "Extracted: " + files + " files, " + dirs + " directories." + hint);
        } catch (Throwable e) {
            return ToolResult.failure("Failed to import world from '" + zipFileName + "': " + e.getMessage());
        }
    }

    /// Normalizes a zip entry name: converts backslashes to forward slashes and drops any
    /// leading slashes so it is always treated as a relative path.
    private static String normalizeName(String raw) {
        String name = raw.replace('\\', '/');
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        return name;
    }

    private static String stripZipExtension(String fileName) {
        int dot = fileName.toLowerCase(Locale.ROOT).lastIndexOf(".zip");
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
