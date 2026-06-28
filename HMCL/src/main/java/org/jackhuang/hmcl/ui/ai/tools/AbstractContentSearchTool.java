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
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;

import java.util.Map;

/// Base class for the content-search AI tools.
///
/// Subclasses fix the content [RemoteAddonRepository.Type] and the default remote source.
/// Searching reuses {@link RemoteAddonRepository#search} via {@link ContentToolSupport}.
abstract class AbstractContentSearchTool implements Tool {

    private static final int DEFAULT_LIMIT = 10;

    private final RemoteAddonRepository.Type type;
    private final ContentToolSupport.Source defaultSource;

    AbstractContentSearchTool(RemoteAddonRepository.Type type, ContentToolSupport.Source defaultSource) {
        this.type = type;
        this.defaultSource = defaultSource;
    }

    @Override
    public final ToolResult execute(Map<String, Object> parameters) {
        String query = ContentToolSupport.primaryParam(parameters, "query");
        if (query == null) {
            return ToolResult.failure("Missing required parameter: query (the search keywords).");
        }

        ContentToolSupport.Source source = parameters.containsKey("source")
                ? ContentToolSupport.parseSource(ContentToolSupport.optional(parameters, "source"))
                : defaultSource;

        RemoteAddonRepository repository = ContentToolSupport.repositoryFor(source, type);
        if (repository == null) {
            return ToolResult.failure("Source '" + source + "' does not provide this content type. Try a different source.");
        }
        if (source == ContentToolSupport.Source.CURSEFORGE && !ContentToolSupport.isCurseForgeAvailable()) {
            return ToolResult.failure("CurseForge is not configured (no API key in this build). Use the Modrinth source instead.");
        }

        String gameVersion = ContentToolSupport.optional(parameters, "game_version");

        try {
            return ToolResult.success(ContentToolSupport.search(repository, gameVersion, query, DEFAULT_LIMIT));
        } catch (Exception e) {
            return ToolResult.failure("Search failed: " + messageOf(e));
        }
    }

    static String messageOf(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
