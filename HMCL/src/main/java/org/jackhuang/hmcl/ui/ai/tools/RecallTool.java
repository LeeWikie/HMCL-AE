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

import org.jackhuang.hmcl.ai.remember.RememberStore;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/// Retrieves facts previously stored via the remember tool, by full-text search
/// over the file-based memory store (no embedding model — pure text match,
/// hermes-style).
@NotNullByDefault
public final class RecallTool implements Tool {

    private final RememberStore store;

    public RecallTool(RememberStore store) {
        this.store = store;
    }

    @Override
    public String getName() {
        return "recall";
    }

    @Override
    public String getDescription() {
        return "Searches the global AI memory for facts stored earlier via remember. "
                + "Parameters: query (optional free-text; empty = list all), tag (optional tag filter), "
                + "limit (optional max results, default 10). Returns matching memories with their content.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String query = String.valueOf(parameters.getOrDefault("query", "")).trim();
        Object tagObj = parameters.get("tag");
        @Nullable String tag = tagObj instanceof String && !((String) tagObj).isBlank() ? ((String) tagObj).trim() : null;
        int limit = InstanceToolSupport.parseInt(parameters, "limit", 10);
        if (limit <= 0) limit = 10;

        try {
            store.init();
            List<RememberStore.Entry> entries = store.recall(query, tag, limit);
            if (entries.isEmpty()) {
                return ToolResult.success("No memories" + (query.isEmpty() ? "" : " match \"" + query + "\"") + ".");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(entries.size()).append(" memor").append(entries.size() == 1 ? "y" : "ies").append(":\n\n");
            for (RememberStore.Entry e : entries) {
                sb.append("### ").append(e.getTitle() != null ? e.getTitle() : "(untitled)");
                if (e.getTags() != null && !e.getTags().isEmpty()) {
                    sb.append("  [").append(String.join(", ", e.getTags())).append(']');
                }
                sb.append('\n');
                String content = e.getContent();
                if (content != null && !content.isBlank()) {
                    sb.append(content.strip()).append('\n');
                }
                sb.append('\n');
            }
            return ToolResult.success(sb.toString().trim());
        } catch (IOException e) {
            return ToolResult.failure("Failed to recall memories: " + e.getMessage());
        }
    }
}
