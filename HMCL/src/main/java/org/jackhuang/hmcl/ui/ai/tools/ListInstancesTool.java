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
import org.jackhuang.hmcl.game.LauncherHelper;
import org.jackhuang.hmcl.game.Version;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/// A read-only tool that lists the installed Minecraft instances (versions) of
/// the currently selected HMCL profile.
///
/// This reuses HMCL's launcher APIs directly:
/// - [`Profiles#getSelectedProfile()`] for the active profile,
/// - [`Profile#getRepository()`] / [`HMCLGameRepository#getDisplayVersions()`]
///   for the user-visible instance list,
/// - [`HMCLGameRepository#getRunDirectory(String)`] /
///   [`HMCLGameRepository#getModsDirectory(String)`] for per-instance paths,
/// - [`HMCLGameRepository#getGameVersion(String)`] for the Minecraft version,
/// - [`LibraryAnalyzer#analyze`] over
///   [`HMCLGameRepository#getResolvedPreservingPatchesVersion(String)`] for the
///   installed mod loaders (the same detection [`InstanceDetailsTool`] uses),
///   plus a local mods-folder scan for the mod count.
///
/// All enrichment is best-effort, local-disk only (no network): if a single
/// instance cannot be analyzed, only that instance's extra fields are omitted —
/// the overall listing never fails.
///
/// Permission level: READ_ONLY. It never modifies any launcher state.
@NotNullByDefault
public final class ListInstancesTool implements Tool {

    @Override
    public String getName() {
        return "list_instances";
    }

    @Override
    public String getDescription() {
        return "Lists the installed Minecraft instances (versions) of the currently selected HMCL profile. "
                + "Takes no parameters. Returns each instance's id, release type, Minecraft version, installed mod "
                + "loaders (Forge/NeoForge/Fabric/Quilt/OptiFine/LiteLoader) with their versions, installed-mod count, "
                + "run directory, mods directory, and whether HMCL currently has it running; marks the currently "
                + "selected instance. Read-only. Because version/loaders/mod-count are already included here, you do "
                + "NOT need instance_details just to answer which instances are a given version or have a given loader; "
                + "instance_details only adds modpack origin for one instance. "
                + "'running' can only be true for instances HMCL itself launched and is still tracking — it cannot "
                + "see a copy of the game started outside HMCL.";
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
        // The repository is lazily loaded; make sure the version list is populated.
        if (!repository.isLoaded()) {
            try {
                repository.refreshVersions();
            } catch (Throwable e) {
                return ToolResult.failure("Failed to load installed instances: " + e.getMessage());
            }
        }

        String selectedInstance = Profiles.getSelectedInstance();

        List<Version> versions = repository.getDisplayVersions().toList();
        if (versions.isEmpty()) {
            return ToolResult.success("No Minecraft instances are installed in the selected profile.\n"
                    + "Game directory: " + repository.getBaseDirectory());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Profile: ").append(Profiles.getProfileDisplayName(profile)).append('\n');
        sb.append("Game directory: ").append(repository.getBaseDirectory()).append('\n');
        sb.append("Installed instances (").append(versions.size()).append("):\n");

        for (Version version : versions) {
            String id = version.getId();
            boolean selected = id.equals(selectedInstance);
            sb.append(selected ? "  * " : "  - ").append(id);
            if (version.getType() != null) {
                sb.append(" [").append(version.getType().name().toLowerCase(Locale.ROOT)).append(']');
            }
            if (selected) {
                sb.append(" (selected)");
            }
            if (LauncherHelper.isInstanceRunning(id)) {
                sb.append(" (running)");
            }
            sb.append('\n');

            // Enriched, best-effort info — all local-disk reads, no network. Any single
            // failure only drops that one line; it never aborts the listing.
            @Nullable String gameVersion = null;
            try {
                gameVersion = repository.getGameVersion(id).orElse(null);
            } catch (Throwable ignored) {
                // Resolution can fail on a broken version json; reported as unknown below.
            }
            sb.append("      version: ").append(gameVersion != null ? gameVersion : "unknown").append('\n');

            // Mod loaders, via the same analyzer HMCL's UI / instance_details uses.
            try {
                Version resolved = repository.getResolvedPreservingPatchesVersion(id);
                LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(resolved, gameVersion);
                List<String> loaders = new ArrayList<>();
                String minecraftPatchId = LibraryAnalyzer.LibraryType.MINECRAFT.getPatchId();
                for (LibraryAnalyzer.LibraryMark mark : analyzer) {
                    String markId = mark.getLibraryId();
                    if (markId.equals(minecraftPatchId)) {
                        continue;
                    }
                    String markVersion = mark.getLibraryVersion();
                    loaders.add(markVersion != null && !markVersion.isEmpty() ? markId + " " + markVersion : markId);
                }
                sb.append("      loaders: ")
                        .append(loaders.isEmpty() ? "none (vanilla)" : String.join(", ", loaders))
                        .append('\n');
            } catch (Throwable ignored) {
                // Could not analyze this instance's libraries; skip the loaders line.
            }

            Path modsDirectory = repository.getModsDirectory(id);
            try {
                if (Files.isDirectory(modsDirectory)) {
                    int[] counts = countMods(modsDirectory);
                    sb.append("      mods   : ").append(counts[0]).append(" enabled");
                    if (counts[1] > 0) {
                        sb.append(", ").append(counts[1]).append(" disabled");
                    }
                    sb.append('\n');
                }
            } catch (Throwable ignored) {
                // Could not scan the mods folder; skip the mod-count line.
            }

            Path runDirectory = repository.getRunDirectory(id);
            sb.append("      runDir : ").append(runDirectory).append('\n');
            sb.append("      modsDir: ").append(modsDirectory).append('\n');
        }

        return ToolResult.success(sb.toString().trim());
    }

    /// Returns {@code [enabled, disabled]} mod counts for an existing mods folder.
    /// Mirrors the count logic in [`InstanceDetailsTool`]; local-disk only.
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
