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
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/// Domain tool for querying EXTERNAL content sources (search only — install/list-local live on
/// the `instance` tool). Parameters are the union of the wrapped tools' own; see each one's
/// javadoc for the exact fields per action.
///
/// The four addon-search actions (mods/resourcepacks/shaders/modpacks) can query Modrinth AND
/// CurseForge at once: when the caller does not pin a specific `source`, both are queried
/// CONCURRENTLY and merged, so a stalled/unavailable source never blocks the other one out — the
/// gap a real trace surfaced (a model stuck retrying the same source instead of switching).
@NotNullByDefault
public final class SearchTool implements ToolSpec {

    private final SearchModsTool mods = new SearchModsTool();
    private final SearchResourcePacksTool resourcepacks = new SearchResourcePacksTool();
    private final SearchShadersTool shaders = new SearchShadersTool();
    private final SearchModpacksTool modpacks = new SearchModpacksTool();
    private final SearchWorldsTool worlds = new SearchWorldsTool(); // CurseForge-only, no dual-source
    private final ListGameVersionsTool gameVersions = new ListGameVersionsTool();

    @Override
    public String getName() {
        return "search";
    }

    @Override
    public String getDescription() {
        return "Searches EXTERNAL Minecraft content sources — installing/listing what's already local is the "
                + "'instance' tool's job, not this one's. Parameter 'action' (required): "
                + "mods, resourcepacks, shaders, modpacks — each takes query (required), gameVersion (optional), "
                + "source (optional: \"modrinth\", \"curseforge\", or OMITTED to query BOTH concurrently and "
                + "merge — prefer omitting it unless the user asked for one source specifically); "
                + "worlds — query (required), game_version (optional); CurseForge only. "
                + "game_versions — the REAL, live Minecraft version list; call this before asking the user "
                + "which version, never rely on training memory. "
                + "Use the returned id with the matching instance(action=..._install) action.";
    }

    @Override
    public boolean supportsStructuredSchema() {
        return true;
    }

    @Override
    public String getInputSchemaJson() {
        return """
               {
                 "$schema": "https://json-schema.org/draft/2020-12/schema",
                 "type": "object",
                 "properties": {
                   "action": {"type": "string", "enum": ["mods", "resourcepacks", "shaders", "modpacks", "worlds", "game_versions"], "description": "Which content type / operation."},
                   "query": {"type": "string", "description": "Search keywords (required for mods/resourcepacks/shaders/modpacks/worlds)."},
                   "gameVersion": {"type": "string", "description": "Optional Minecraft version filter, e.g. \\"1.20.1\\"; empty/omitted matches any."},
                   "loader": {"type": "string", "description": "mods: optional loader hint (fabric/forge/neoforge/quilt) — not a hard filter."},
                   "source": {"type": "string", "enum": ["modrinth", "curseforge"], "description": "Pin one source; OMIT to query Modrinth + CurseForge concurrently and merge (preferred default)."},
                   "game_version": {"type": "string", "description": "worlds: optional Minecraft version filter (note the underscore, unlike gameVersion elsewhere)."}
                 },
                 "required": ["action"]
               }
               """;
    }

    @Override
    public ToolPermission getPermission(Map<String, Object> parameters) {
        return ToolPermission.READ_ONLY; // every action here is a read-only network query
    }

    /// Every action this tool exposes is READ_ONLY (see {@link #getPermission(Map)}) — overridden
    /// so the settings/catalog UI (and Plan Mode's per-action gating) report that accurately instead
    /// of falling back to the no-arg {@link #getPermission()} default of CONTROLLED_WRITE, which
    /// this tool never actually resolves to for any real action.
    @Override
    public ToolPermission getMaxPermission() {
        return ToolPermission.READ_ONLY;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String action = actionOf(parameters);
        return switch (action) {
            case "mods" -> dualSource(mods, "Modrinth", "CurseForge", parameters);
            case "resourcepacks" -> dualSource(resourcepacks, "Modrinth", "CurseForge", parameters);
            case "shaders" -> dualSource(shaders, "Modrinth", "CurseForge", parameters);
            case "modpacks" -> dualSource(modpacks, "Modrinth", "CurseForge", parameters);
            case "worlds" -> worlds.execute(parameters);
            case "game_versions" -> gameVersions.execute(parameters);
            default -> ToolResult.failure("Unknown action '" + action + "'. Valid actions: mods, resourcepacks, "
                    + "shaders, modpacks, worlds, game_versions.");
        };
    }

    /// Runs {@code tool} once if the caller pinned a specific `source`; otherwise queries
    /// Modrinth and CurseForge concurrently on {@link ContentToolSupport}'s shared worker pool and
    /// merges both outputs into one result — a hung/unavailable source is reported inline instead
    /// of failing the whole call or making the caller wait for a serial retry-then-switch.
    private static ToolResult dualSource(Tool tool, String sourceALabel, String sourceBLabel,
                                          Map<String, Object> parameters) {
        Object sourceParam = parameters.get("source");
        String pinned = sourceParam != null ? sourceParam.toString().trim().toLowerCase(Locale.ROOT) : "";
        if (pinned.equals("modrinth") || pinned.equals("curseforge")) {
            return tool.execute(parameters);
        }

        CompletableFuture<ToolResult> a = CompletableFuture.supplyAsync(
                () -> tool.execute(withSource(parameters, "modrinth")), ContentToolSupport.worker());
        CompletableFuture<ToolResult> b = CompletableFuture.supplyAsync(
                () -> tool.execute(withSource(parameters, "curseforge")), ContentToolSupport.worker());

        ToolResult resultA = safeGet(a);
        ToolResult resultB = safeGet(b);

        StringBuilder sb = new StringBuilder();
        appendSection(sb, sourceALabel, resultA);
        sb.append('\n');
        appendSection(sb, sourceBLabel, resultB);
        return ToolResult.success(sb.toString().trim());
    }

    private static Map<String, Object> withSource(Map<String, Object> parameters, String source) {
        Map<String, Object> copy = new HashMap<>(parameters);
        copy.put("source", source);
        return copy;
    }

    private static ToolResult safeGet(CompletableFuture<ToolResult> future) {
        try {
            ToolResult result = future.get(70, TimeUnit.SECONDS);
            return result != null ? result : ToolResult.failure("No result returned.");
        } catch (Throwable e) {
            future.cancel(true);
            return ToolResult.failure(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private static void appendSection(StringBuilder sb, String label, ToolResult result) {
        sb.append("== ").append(label).append(" ==\n");
        sb.append(result.isSuccess() ? result.getOutput() : "(unavailable: " + result.getError() + ")");
        sb.append('\n');
    }

    private static String actionOf(Map<String, Object> parameters) {
        Object action = parameters.get("action");
        return action != null ? action.toString().trim().toLowerCase(Locale.ROOT) : "";
    }
}
