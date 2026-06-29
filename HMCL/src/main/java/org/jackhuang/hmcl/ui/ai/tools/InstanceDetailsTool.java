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
import org.jackhuang.hmcl.download.LibraryAnalyzer;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.Version;
import org.jackhuang.hmcl.modpack.ModpackConfiguration;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/// A read-only diagnostic tool that summarises a single Minecraft instance: its
/// Minecraft version, the installed mod loaders (Forge / NeoForge / Fabric /
/// Quilt / OptiFine / LiteLoader …) with their versions, installed-mod counts,
/// whether it came from a modpack, and its directories.
///
/// This reuses HMCL's native APIs directly:
/// - [`HMCLGameRepository#getGameVersion(String)`] for the Minecraft version,
/// - [`LibraryAnalyzer#analyze`] over
///   [`HMCLGameRepository#getResolvedPreservingPatchesVersion(String)`] for loaders
///   (the same detection HMCL's instance list uses),
/// - [`HMCLGameRepository#getRunDirectory(String)`] for paths and mod counts,
/// - [`HMCLGameRepository#readModpackConfiguration(String)`] for modpack origin.
///
/// Memory / JVM-args / Java-path are intentionally NOT reported here: HMCL stores
/// them in the rewritten GameSettings system; use the `config-hmcl` skill to view
/// or edit those.
///
/// Permission level: READ_ONLY. It never modifies any launcher state.
@NotNullByDefault
public final class InstanceDetailsTool implements Tool {

    @Override
    public String getName() {
        return "instance_details";
    }

    @Override
    public String getDescription() {
        return "Shows a diagnostic summary of one Minecraft instance: Minecraft version, installed mod loaders "
                + "(Forge/NeoForge/Fabric/Quilt/OptiFine/LiteLoader) and their versions, enabled/disabled mod counts, "
                + "whether it originated from a modpack, and its run directory. "
                + "Parameter: instance (optional; defaults to the currently selected instance). Read-only. "
                + "For memory / JVM args / Java path use the config-hmcl skill instead.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Profile profile;
        try {
            profile = Profiles.getSelectedProfile();
        } catch (Throwable e) {
            return ToolResult.failure("No profile is currently selected: " + e.getMessage());
        }
        HMCLGameRepository repository = profile.getRepository();
        if (!repository.isLoaded()) {
            try {
                repository.refreshVersions();
            } catch (Throwable e) {
                return ToolResult.failure("Failed to load installed instances: " + e.getMessage());
            }
        }

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

        if (!repository.hasVersion(instance)) {
            return ToolResult.failure("Instance '" + instance + "' does not exist in the selected profile. "
                    + "Use list_instances to see available instances.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Instance: ").append(instance);
        if (instance.equals(Profiles.getSelectedInstance())) {
            sb.append(" (selected)");
        }
        sb.append('\n');

        // Minecraft version.
        @Nullable String gameVersion = null;
        try {
            gameVersion = repository.getGameVersion(instance).orElse(null);
        } catch (Throwable ignored) {
            // Resolution can fail on a broken version json; reported as unknown below.
        }
        sb.append("Minecraft version: ").append(gameVersion != null ? gameVersion : "unknown").append('\n');

        // Mod loaders, via the same analyzer HMCL's UI uses.
        try {
            Version resolved = repository.getResolvedPreservingPatchesVersion(instance);
            LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(resolved, gameVersion);
            List<String> loaders = new ArrayList<>();
            String minecraftPatchId = LibraryAnalyzer.LibraryType.MINECRAFT.getPatchId();
            for (LibraryAnalyzer.LibraryMark mark : analyzer) {
                String id = mark.getLibraryId();
                if (id.equals(minecraftPatchId)) {
                    continue;
                }
                String version = mark.getLibraryVersion();
                loaders.add(version != null && !version.isEmpty() ? id + " " + version : id);
            }
            if (loaders.isEmpty()) {
                sb.append("Mod loader: none (vanilla)\n");
            } else {
                sb.append("Mod loaders: ").append(String.join(", ", loaders)).append('\n');
            }
        } catch (Throwable e) {
            sb.append("Mod loaders: (could not analyze: ").append(e.getMessage()).append(")\n");
        }

        // Modpack origin.
        try {
            ModpackConfiguration<?> modpack = repository.readModpackConfiguration(instance);
            if (modpack != null) {
                sb.append("Modpack: yes");
                String name = modpack.getName();
                String version = modpack.getVersion();
                if (name != null && !name.isEmpty()) {
                    sb.append(" — ").append(name);
                }
                if (version != null && !version.isEmpty()) {
                    sb.append(" (").append(version).append(')');
                }
                sb.append('\n');
            }
        } catch (Throwable ignored) {
            // Not a modpack-managed instance, or config unreadable; omit the line.
        }

        // Directories + mod counts.
        try {
            Path runDir = repository.getRunDirectory(instance);
            sb.append("Run directory: ").append(runDir).append('\n');

            Path modsDir = runDir.resolve("mods");
            if (!Files.isDirectory(modsDir)) {
                sb.append("Mods: no mods folder\n");
            } else {
                int[] counts = countMods(modsDir);
                sb.append("Mods: ").append(counts[0]).append(" enabled");
                if (counts[1] > 0) {
                    sb.append(", ").append(counts[1]).append(" disabled");
                }
                sb.append('\n');
            }
        } catch (Throwable e) {
            sb.append("Run directory: (could not resolve: ").append(e.getMessage()).append(")\n");
        }

        return ToolResult.success(sb.toString().trim());
    }

    /// Returns {@code [enabled, disabled]} mod counts for an existing mods folder.
    private static int[] countMods(Path modsDir) {
        int enabled = 0;
        int disabled = 0;
        try (Stream<Path> stream = Files.list(modsDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(p)) {
                    continue;
                }
                String name = p.getFileName().toString();
                if (name.endsWith(".jar")) {
                    enabled++;
                } else if (name.endsWith(".jar.disabled")) {
                    disabled++;
                }
            }
        } catch (Throwable e) {
            return new int[]{enabled, disabled};
        }
        return new int[]{enabled, disabled};
    }
}
