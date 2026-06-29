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
import org.jackhuang.hmcl.modpack.ModpackExportInfo;
import org.jackhuang.hmcl.modpack.modrinth.ModrinthModpackExportTask;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.task.Task;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/// A controlled-write tool that exports an installed instance to a Modrinth
/// modpack file (`.mrpack`).
///
/// This reuses HMCL's native export pipeline directly:
/// [`ModrinthModpackExportTask`] which walks the instance's run directory,
/// bundles mods/resourcepacks/shaders/config as `client-overrides`, derives the
/// Minecraft + loader versions via [`LibraryAnalyzer`], and writes a valid
/// `modrinth.index.json`. The HMCL [`Task`] is run to completion through
/// [`ContentToolSupport#runTaskBlocking`] with a generous timeout.
///
/// The Modrinth format is chosen because it can be produced with no extra
/// metadata: [`ModpackExportInfo#setNoCreateRemoteFiles`] is enabled so every
/// file is bundled offline (no per-file Modrinth/CurseForge hash lookups), and an
/// empty whitelist means "include everything that is not blacklisted".
///
/// Other formats (Mcbbs, Server, MultiMC) are intentionally not implemented here:
/// they require additional metadata or configuration (Server needs a file API
/// URL, MultiMC needs a full instance configuration, Mcbbs requires author / url /
/// launch-argument fields) that cannot be supplied automatically, so exposing
/// them would either fail or produce an incomplete pack.
///
/// Permission level: CONTROLLED_WRITE. It writes a single new modpack file and
/// never modifies the instance itself.
@NotNullByDefault
public final class ExportModpackTool implements Tool {

    /// Maximum time to wait for the export task, in seconds.
    private static final int EXPORT_TIMEOUT_SECONDS = 600;

    @Override
    public String getName() {
        return "export_modpack";
    }

    @Override
    public String getDescription() {
        return "Exports an installed instance to a Modrinth modpack file (.mrpack). "
                + "Parameters: instance (string, optional: the instance/version id - defaults to the selected instance), "
                + "target (string, optional: absolute output path for the .mrpack file - defaults to <gameDir>/<instance>.mrpack), "
                + "format (string, optional: only \"modrinth\" is supported and is the default), "
                + "name/version/author/description (strings, optional: modpack metadata). "
                + "All mods/config/resourcepacks/shaders are bundled offline (no per-file remote lookups). "
                + "This writes a new modpack file to disk; it does not change the instance.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String instance = String.valueOf(parameters.getOrDefault("instance", "")).trim();
        String format = String.valueOf(parameters.getOrDefault("format", "modrinth")).trim().toLowerCase(Locale.ROOT);
        String targetOverride = optional(parameters, "target");
        String name = optional(parameters, "name");
        String packVersion = optional(parameters, "version");
        String author = optional(parameters, "author");
        String description = optional(parameters, "description");

        if (!format.isEmpty() && !format.startsWith("modrinth") && !format.equals("mrpack")) {
            return ToolResult.failure("Unsupported format '" + format + "'. Only \"modrinth\" (.mrpack) is supported by this tool.");
        }

        Profile profile;
        HMCLGameRepository repository;
        String target;
        try {
            profile = Profiles.getSelectedProfile();
            repository = profile.getRepository();
            target = instance.isEmpty() ? Profiles.getSelectedInstance() : instance;
            if (target == null || target.isEmpty()) {
                return ToolResult.failure("No instance selected. Use list_instances, or pass instance.");
            }
            if (!repository.hasVersion(target)) {
                return ToolResult.failure("No such instance '" + target + "'. Use list_instances.");
            }
        } catch (Throwable t) {
            return ToolResult.failure("Could not resolve the instance: " + t.getMessage());
        }

        // Resolve the output path.
        Path output;
        try {
            if (targetOverride != null) {
                output = Path.of(targetOverride).toAbsolutePath().normalize();
                if (Files.isDirectory(output)) {
                    output = output.resolve(sanitize(target) + ".mrpack");
                } else if (!output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mrpack")) {
                    output = output.resolveSibling(output.getFileName() + ".mrpack");
                }
            } else {
                output = repository.getBaseDirectory().resolve(sanitize(target) + ".mrpack").toAbsolutePath().normalize();
            }
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
        } catch (Throwable t) {
            return ToolResult.failure("Could not prepare the output path: " + t.getMessage());
        }

        if (Files.exists(output)) {
            return ToolResult.failure("Output file already exists: " + output
                    + ". Pass a different 'target' to avoid overwriting it.");
        }

        // Build export metadata. validate() is a no-op, and the Modrinth task only
        // reads name/version/description, so safe defaults are sufficient.
        ModpackExportInfo info = new ModpackExportInfo()
                .setName(name != null ? name : target)
                .setVersion(packVersion != null ? packVersion : "1.0.0")
                .setAuthor(author != null ? author : "")
                .setDescription(description != null ? description : "Exported from " + target + " by HMCL-AE");
        // Bundle every file directly (offline); do not attempt per-file remote hash lookups.
        info.setNoCreateRemoteFiles(true);
        info.setSkipCurseForgeRemoteFiles(true);
        // Empty whitelist => include everything that is not on the modpack blacklist.

        Task<?> task = new ModrinthModpackExportTask(repository, target, info, output);
        try {
            ContentToolSupport.runTaskBlocking(task, EXPORT_TIMEOUT_SECONDS, "Export");
        } catch (Exception e) {
            return ToolResult.failure("Export of instance '" + target + "' failed: "
                    + AbstractContentSearchTool.messageOf(e));
        }

        String size;
        try {
            size = " (" + (Files.size(output) / 1024 / 1024) + " MiB)";
        } catch (IOException e) {
            size = "";
        }
        return ToolResult.success("Exported instance '" + target + "' to Modrinth modpack:\n  "
                + output + size);
    }

    @Nullable
    private static String optional(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return null;
    }

    private static String sanitize(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "modpack" : s;
    }
}
