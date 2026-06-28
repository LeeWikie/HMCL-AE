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

import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.RemoteAddonRepository;
import org.jackhuang.hmcl.addon.repository.CurseForgeRemoteAddonRepository;
import org.jackhuang.hmcl.addon.repository.ModrinthRemoteAddonRepository;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/// A read-only tool that searches for Minecraft mods on Modrinth (and optionally
/// CurseForge) and returns a concise list of matches.
///
/// This reuses HMCL's launcher APIs directly:
/// - [`ModrinthRemoteAddonRepository#MODS`] /
///   [`CurseForgeRemoteAddonRepository#MODS`] as the remote repositories,
/// - [`RemoteAddonRepository#search`] for the query,
/// - [`DownloadProviders#getDownloadProvider()`] for the active download source.
///
/// Permission level: READ_ONLY. It performs network reads only and never writes.
@NotNullByDefault
public final class SearchModsTool implements Tool {

    /// Maximum number of results returned to the model.
    private static final int MAX_RESULTS = 20;

    @Override
    public String getName() {
        return "search_mods";
    }

    @Override
    public String getDescription() {
        return "Searches for Minecraft mods on Modrinth (default) or CurseForge. "
                + "Parameters: query (string, required search text), "
                + "gameVersion (string, optional Minecraft version like \"1.20.1\"; empty matches any), "
                + "loader (string, optional: fabric/forge/neoforge/quilt - used to hint the user, not a hard filter), "
                + "source (string, optional: \"modrinth\" (default) or \"curseforge\"). "
                + "Returns up to " + MAX_RESULTS + " results with slug, title, author and short description. "
                + "Use the returned slug with install_mod to install. Read-only.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String query = extractString(parameters, "query", null);
        if (query == null || query.isBlank()) {
            // Allow the generic "query" fallback used by the tool adapter.
            return ToolResult.failure("Missing required parameter: query");
        }

        String gameVersion = extractString(parameters, "gameVersion", "");
        String loader = extractString(parameters, "loader", null);
        String source = extractString(parameters, "source", "modrinth");

        RemoteAddonRepository repository;
        if ("curseforge".equalsIgnoreCase(source)) {
            if (!CurseForgeRemoteAddonRepository.isAvailable()) {
                return ToolResult.failure("CurseForge is not available (no API key configured). Use source=\"modrinth\".");
            }
            repository = CurseForgeRemoteAddonRepository.MODS;
        } else {
            repository = ModrinthRemoteAddonRepository.MODS;
        }

        DownloadProvider downloadProvider = DownloadProviders.getDownloadProvider();

        List<RemoteAddon> results;
        try {
            RemoteAddonRepository.SearchResult searchResult = repository.search(
                    downloadProvider,
                    gameVersion == null ? "" : gameVersion,
                    null,
                    0,
                    MAX_RESULTS,
                    query,
                    RemoteAddonRepository.SortType.POPULARITY,
                    RemoteAddonRepository.SortOrder.DESC);
            results = searchResult.getResults().limit(MAX_RESULTS).toList();
        } catch (Throwable e) {
            return ToolResult.failure("Mod search failed: " + e.getMessage());
        }

        if (results.isEmpty()) {
            return ToolResult.success("No mods found for query '" + query + "'"
                    + (gameVersion.isBlank() ? "" : " (game version " + gameVersion + ")") + ".");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" mod(s) on ")
                .append("curseforge".equalsIgnoreCase(source) ? "CurseForge" : "Modrinth")
                .append(" for '").append(query).append("'");
        if (!gameVersion.isBlank()) {
            sb.append(" (game version ").append(gameVersion).append(')');
        }
        if (loader != null && !loader.isBlank()) {
            sb.append(" (loader hint: ").append(loader).append(')');
        }
        sb.append(":\n");

        for (RemoteAddon addon : results) {
            sb.append("  - ").append(addon.slug());
            if (addon.title() != null && !addon.title().isBlank()) {
                sb.append(" — ").append(addon.title());
            }
            if (addon.author() != null && !addon.author().isBlank()) {
                sb.append(" (by ").append(addon.author()).append(')');
            }
            sb.append('\n');
            String description = addon.description();
            if (description != null && !description.isBlank()) {
                sb.append("      ").append(truncate(description)).append('\n');
            }
        }

        sb.append("\nTo install one, call install_mod with id=<slug> and source=")
                .append("curseforge".equalsIgnoreCase(source) ? "\"curseforge\"" : "\"modrinth\"").append('.');

        return ToolResult.success(sb.toString());
    }

    /// Truncates a description to a single short line for compact display.
    private static String truncate(String text) {
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 160 ? oneLine.substring(0, 157) + "..." : oneLine;
    }

    @Nullable
    private static String extractString(Map<String, Object> params, String key, @Nullable String fallback) {
        Object val = params.get(key);
        if (val instanceof String s && !s.isEmpty()) {
            return s;
        }
        return fallback;
    }
}
