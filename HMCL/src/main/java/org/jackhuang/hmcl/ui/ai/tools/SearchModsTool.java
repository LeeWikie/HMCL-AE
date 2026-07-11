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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    /// Cap on how many of the search results get a per-mod dependency lookup. Each lookup is an
    /// extra network round trip ([`RemoteAddon.IMod#loadVersions`]), so this is deliberately
    /// smaller than [`#MAX_RESULTS`] to keep the tool's API cost bounded even for broad queries.
    private static final int MAX_DEPENDENCY_LOOKUPS = 8;

    @Override
    public String getName() {
        return "search_mods";
    }

    @Override
    public String getDescription() {
        return "Searches for Minecraft mods on Modrinth or CurseForge. "
                + "Parameters: query (string, required search text), "
                + "gameVersion (string, optional Minecraft version like \"1.20.1\"; empty matches any), "
                + "loader (string, optional: fabric/forge/neoforge/quilt - NOT sent to the underlying search "
                + "(that API call takes no loader parameter); it only hints the query as a reminder of what "
                + "the user asked for and is echoed back into the result summary, it does not filter or rank "
                + "results), "
                + "source (string, optional: \"modrinth\" or \"curseforge\"; when omitted, follows the user's "
                + "default addon source launcher setting, falling back to Modrinth if CurseForge has no API key). "
                + "Returns up to " + MAX_RESULTS + " results with slug, title, author, short description, and "
                + "(for the top " + MAX_DEPENDENCY_LOOKUPS + " results) a 'requires' line listing required/optional "
                + "dependency ids of the best-matching version, when any exist. "
                + "Use the returned slug with instance(action=\"mods_install\") to install. Read-only. "
                + "Note: results are NOT individually verified against 'loader' — search never filters on it. "
                + "Confirm actual loader/version support at install time via mods_install's error (it DOES "
                + "hard-filter and will report what's really available).";
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
        // When the model gives no explicit source, follow the user's "default addon source"
        // launcher preference (with the no-API-key CurseForge → Modrinth fallback) instead of
        // hard-coding Modrinth. An explicit source keeps its strict failure semantics below.
        String source = extractString(parameters, "source", null);
        if (source == null) {
            source = DefaultAddonSource.preferredName();
        }

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

        final String effectiveGameVersion = gameVersion == null ? "" : gameVersion;
        List<RemoteAddon> results;
        try {
            // Run the search through the shared, timeout-guarded helper so this tool
            // behaves like every other content-search tool (operation-level timeout).
            RemoteAddonRepository.SearchResult searchResult = ContentToolSupport.callWithTimeout(
                    () -> repository.search(
                            downloadProvider,
                            effectiveGameVersion,
                            null,
                            0,
                            MAX_RESULTS,
                            query,
                            RemoteAddonRepository.SortType.POPULARITY,
                            RemoteAddonRepository.SortOrder.DESC),
                    60, "Search");
            results = searchResult.getResults().limit(MAX_RESULTS).toList();
        } catch (Throwable e) {
            return ToolResult.failure("Mod search failed: " + e.getMessage());
        }

        if (results.isEmpty()) {
            return ToolResult.success("No mods found for query '" + query + "'"
                    + (gameVersion.isBlank() ? "" : " (game version " + gameVersion + ")") + ".");
        }

        Map<String, String> dependencyHints = fetchDependencyHints(repository, downloadProvider, results, effectiveGameVersion);

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
            String deps = dependencyHints.get(addon.slug());
            if (deps != null) {
                sb.append("      requires: ").append(deps).append('\n');
            }
        }

        sb.append("\nTo install one, call instance(action=\"mods_install\", id=<slug>, source=")
                .append("curseforge".equalsIgnoreCase(source) ? "\"curseforge\"" : "\"modrinth\"").append(").");

        return ToolResult.success(sb.toString());
    }

    /// Best-effort per-mod dependency lookup for the top [`#MAX_DEPENDENCY_LOOKUPS`] results.
    /// For each of those addons, loads its versions, picks the one matching `gameVersion` (or the
    /// first/latest if `gameVersion` is blank or no version matches), and collects the ids of its
    /// REQUIRED/OPTIONAL dependencies. A lookup failure for one addon (network hiccup, no versions,
    /// timeout) is skipped rather than failing the whole search — dependency info is a bonus on
    /// top of the search results, not something the caller can't proceed without.
    private static Map<String, String> fetchDependencyHints(RemoteAddonRepository repository, DownloadProvider downloadProvider,
                                                              List<RemoteAddon> results, String gameVersion) {
        Map<String, String> hints = new LinkedHashMap<>();
        int limit = Math.min(results.size(), MAX_DEPENDENCY_LOOKUPS);
        for (int i = 0; i < limit; i++) {
            RemoteAddon addon = results.get(i);
            try {
                List<RemoteAddon.Version> versions = ContentToolSupport.callWithTimeout(
                        () -> addon.data().loadVersions(repository, downloadProvider).collect(Collectors.toList()),
                        15, "Version lookup");
                if (versions.isEmpty()) {
                    continue;
                }
                RemoteAddon.Version match = versions.stream()
                        .filter(v -> !gameVersion.isBlank() && v.gameVersions() != null && v.gameVersions().contains(gameVersion))
                        .findFirst()
                        .orElse(versions.get(0));
                List<String> depIds = match.dependencies().stream()
                        .filter(d -> d.getType() == RemoteAddon.DependencyType.REQUIRED || d.getType() == RemoteAddon.DependencyType.OPTIONAL)
                        .map(RemoteAddon.Dependency::getId)
                        .distinct()
                        .collect(Collectors.toList());
                if (!depIds.isEmpty()) {
                    hints.put(addon.slug(), String.join(", ", depIds));
                }
            } catch (Throwable e) {
                // Best-effort: skip this addon's dependency hint rather than failing the search.
            }
        }
        return hints;
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
