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

import org.jackhuang.hmcl.addon.RemoteAddonRepository;
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolResult;

/// AI tool that searches for Minecraft worlds (saves) on CurseForge.
///
/// Only CurseForge exposes a Worlds category; Modrinth does not. There is no matching
/// {@code install_world} tool because HMCL itself provides no world-install pipeline
/// (its Worlds tab only offers "save as", and worlds ship as archives that must be
/// unpacked into {@code <instance>/saves}). This tool therefore surfaces results for
/// discovery only.
public final class SearchWorldsTool extends AbstractContentSearchTool {

    public SearchWorldsTool() {
        super(RemoteAddonRepository.Type.WORLD, ContentToolSupport.Source.CURSEFORGE);
    }

    @Override
    public String getName() {
        return "search_worlds";
    }

    @Override
    public String getDescription() {
        return "Search for Minecraft worlds (saves) on CurseForge. "
                + "Parameters: query (search keywords, required), game_version (optional). "
                + "Requires a CurseForge API key in this build. "
                + "Note: there is no install_world tool; results are for discovery only.";
    }

    /// The model explicitly asked for a non-CurseForge source, but Modrinth has no Worlds category
    /// at all — do not bounce it back to CurseForge and risk the classic ping-pong; delegate to the
    /// welded terminal handler.
    @Override
    ToolResult unavailableSource(ContentToolSupport.Source source) {
        return worldSearchUnavailable();
    }

    /// Reached only when the (default) CurseForge source has no API key. Modrinth cannot serve
    /// worlds either, so there is nowhere to fall back to — weld the terminal state (rewrite #12)
    /// instead of the base class's "use Modrinth instead", which would send the model straight into
    /// a source-switching loop.
    @Override
    ToolResult curseForgeNotConfigured() {
        return worldSearchUnavailable();
    }

    private ToolResult worldSearchUnavailable() {
        if (ContentToolSupport.isCurseForgeAvailable()) {
            // CurseForge works; the model simply picked the wrong source for worlds.
            return ToolFailures.failure(
                    "Modrinth does not offer a Worlds category; worlds are only searchable on CurseForge",
                    ToolFailures.Retryable.YES,
                    "CurseForge is configured and can serve this",
                    "retry with source=\"curseforge\" (the default for search_worlds)");
        }
        // No CurseForge key AND Modrinth structurally has no worlds → there is nowhere to retry.
        return ToolFailures.failure(
                "CurseForge is not configured, and Modrinth does not offer a Worlds category at all",
                ToolFailures.Retryable.NO,
                "world search structurally requires a CurseForge API key in this build",
                "ask the user to configure a CurseForge API key in AI 设置, or skip search and let the "
                        + "user browse/import worlds manually. Do not retry with source=\"modrinth\"");
    }
}
