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
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Read-only tool that parses the metadata of an installed mod jar in an
/// instance's mods folder and reports its mod id, display name, version,
/// supported game version, authors and mod-loader type.
///
/// This reuses HMCL's native mod-parsing pipeline directly:
/// - [`HMCLGameRepository#getModManager(String)`] to obtain a [`ModManager`]
///   bound to the instance,
/// - [`ModManager#getLocalFiles()`] which lazily refreshes and parses every jar
///   with the bundled Forge/NeoForge/Fabric/Quilt/LiteLoader metadata readers,
/// - the resulting [`LocalModFile`] accessors (`getId`, `getName`, `getVersion`,
///   `getGameVersion`, `getAuthors`, `getModLoaderType`, `getDescription`).
///
/// Permission level: READ_ONLY. It never modifies any file.
@NotNullByDefault
public final class GetModInfoTool implements Tool {

    @Override
    public String getName() {
        return "get_mod_info";
    }

    @Override
    public String getDescription() {
        return "Reads the metadata of an installed mod jar in an instance's mods folder. "
                + "Parameters: mod (string, required: a substring of the mod's file name or display name, e.g. \"sodium\"), "
                + "instance (string, optional: the instance/version id - defaults to the selected instance). "
                + "Returns the mod id, display name, version, supported game version, authors, mod-loader type "
                + "(fabric/forge/neoforge/quilt/liteloader) and whether it is enabled. "
                + "If several mods match, a short list is returned so you can refine the query. Read-only.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String query = String.valueOf(parameters.getOrDefault("mod",
                parameters.getOrDefault("query", ""))).trim();
        if (query.isEmpty()) {
            return ToolResult.failure("Missing required parameter: mod (a substring of the mod file/display name).");
        }
        String instance = String.valueOf(parameters.getOrDefault("instance", "")).trim();

        ModManager modManager;
        String target;
        try {
            Profile profile = Profiles.getSelectedProfile();
            HMCLGameRepository repo = profile.getRepository();
            target = instance.isEmpty() ? Profiles.getSelectedInstance() : instance;
            if (target == null || target.isEmpty()) {
                return ToolResult.failure("No instance selected. Use list_instances, or pass instance.");
            }
            if (!repo.hasVersion(target)) {
                return ToolResult.failure("No such instance '" + target + "'. Use list_instances.");
            }
            modManager = repo.getModManager(target);
        } catch (Throwable t) {
            return ToolResult.failure("Could not resolve the instance's mods: " + t.getMessage());
        }

        List<LocalModFile> all;
        try {
            all = new ArrayList<>(modManager.getLocalFiles());
        } catch (Throwable t) {
            return ToolResult.failure("Failed to parse mods in instance '" + target + "': " + t.getMessage());
        }

        if (all.isEmpty()) {
            return ToolResult.success("Instance '" + target + "' has no mods installed.");
        }

        String needle = query.toLowerCase(Locale.ROOT);
        List<LocalModFile> matches = new ArrayList<>();
        for (LocalModFile mod : all) {
            String fileName = safe(mod.getFileName()).toLowerCase(Locale.ROOT);
            String name = safe(mod.getName()).toLowerCase(Locale.ROOT);
            String id = safe(mod.getId()).toLowerCase(Locale.ROOT);
            if (fileName.contains(needle) || name.contains(needle) || id.contains(needle)) {
                matches.add(mod);
            }
        }

        if (matches.isEmpty()) {
            return ToolResult.failure("No installed mod matches \"" + query + "\" in instance '" + target
                    + "'. Use list_mods to see the available mods.");
        }

        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append(matches.size()).append(" mods match \"").append(query)
                    .append("\" in instance '").append(target).append("'. Refine the query:\n");
            for (LocalModFile mod : matches) {
                sb.append("  - ").append(mod.getFileName());
                String name = safe(mod.getName());
                if (!name.isBlank() && !name.equalsIgnoreCase(mod.getFileName())) {
                    sb.append("  (").append(name).append(')');
                }
                sb.append('\n');
            }
            return ToolResult.success(sb.toString().trim());
        }

        LocalModFile mod = matches.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("Mod info for instance '").append(target).append("':\n");
        sb.append("  file       : ").append(mod.getFileName()).append('\n');
        appendIfPresent(sb, "name", mod.getName());
        appendIfPresent(sb, "mod id", mod.getId());
        appendIfPresent(sb, "version", mod.getVersion());
        appendIfPresent(sb, "game ver", mod.getGameVersion());
        appendIfPresent(sb, "authors", mod.getAuthors());
        if (mod.getModLoaderType() != null) {
            sb.append("  loader     : ").append(mod.getModLoaderType().name().toLowerCase(Locale.ROOT)).append('\n');
        }
        appendIfPresent(sb, "url", mod.getUrl());
        sb.append("  enabled    : ").append(mod.isActive()).append('\n');
        if (mod.getDescription() != null) {
            String desc = mod.getDescription().toStringSingleLine();
            if (desc != null && !desc.isBlank()) {
                sb.append("  description: ").append(desc.strip()).append('\n');
            }
        }
        return ToolResult.success(sb.toString().trim());
    }

    private static void appendIfPresent(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("  ").append(pad(label)).append(": ").append(value.strip()).append('\n');
        }
    }

    private static String pad(String label) {
        StringBuilder s = new StringBuilder(label);
        while (s.length() < 10) {
            s.append(' ');
        }
        return s.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
