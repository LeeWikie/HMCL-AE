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
import org.jackhuang.hmcl.ai.tools.ToolFailures;
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
        ModManager modManager;
        String target;
        try {
            Profile profile = Profiles.getSelectedProfile();
            HMCLGameRepository repo = profile.getRepository();
            InstanceToolSupport.ResolvedInstance resolved =
                    InstanceToolSupport.resolveInstance(repo, parameters, false);
            if (resolved.failure() != null) {
                return resolved.failure();
            }
            target = resolved.name();
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
            // Zero-match, like the multi-match branch below, carries the actual candidates — the
            // first N installed mod file names, reusing the already-parsed `all` list (no second
            // read) so a typo is obvious without a separate list_mods round-trip (B10/#19).
            return ToolFailures.failure(
                    "No installed mod matches \"" + query + "\" in instance '" + target + "'",
                    ToolFailures.Retryable.YES,
                    "no installed mod's file name, display name or id contains this substring, which is usually a typo",
                    "installed mods: " + describeInstalledMods(all)
                            + "; use list_mods for the full list, or refine the query");
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
                sb.append("  description: ").append(truncate(desc.strip())).append('\n');
            }
        }
        return ToolResult.success(sb.toString().trim());
    }

    /// The maximum number of installed mod file names carried in a zero-match failure — enough
    /// for the model to spot a typo, bounded so a huge mods folder can't flood the context.
    private static final int MAX_LISTED_MODS = 10;

    /// Lists up to [#MAX_LISTED_MODS] installed mod file names for a zero-match failure, reusing
    /// the already-parsed [LocalModFile] list (the earlier empty-folder branch guarantees this
    /// list is non-empty here), appending a "(N more)" tail when truncated.
    private static String describeInstalledMods(List<LocalModFile> all) {
        int shown = Math.min(all.size(), MAX_LISTED_MODS);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(safe(all.get(i).getFileName()));
        }
        if (all.size() > shown) {
            sb.append(", ... (").append(all.size() - shown).append(" more)");
        }
        return sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("  ").append(pad(label)).append(": ").append(value.strip()).append('\n');
        }
    }

    /// Caps an untrusted, free-text mod description (author-controlled content baked into the
    /// jar, not something HMCL/the user wrote) to a single bounded line before it enters the
    /// model's context — same cap and behaviour as {@link SearchModsTool}'s own `truncate`, so a
    /// long/adversarial description can't inject a much larger untrusted payload here than it
    /// could via a search result for the same mod.
    private static String truncate(String text) {
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 160 ? oneLine.substring(0, 157) + "..." : oneLine;
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
