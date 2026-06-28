/*
 * Hello Minecraft! Launcher
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
package org.jackhuang.hmcl.ai.tools;

/// Extension of {@link Tool} that provides permission, source, and
/// structured-schema metadata for use by {@link ToolRegistry} and
/// LangChain4j adapters.
///
/// Tools that only implement {@link Tool} (without this interface)
/// default to {@link ToolPermission#CONTROLLED_WRITE} /
/// {@link ToolSource#LOCAL} with a flat {@code "query"}-parameter
/// schema.
public interface ToolSpec extends Tool {

    /// Returns the security permission level for this tool.
    default ToolPermission getPermission() {
        return ToolPermission.CONTROLLED_WRITE;
    }

    /// Returns the origin of this tool (local code, MCP, search, etc.).
    default ToolSource getSource() {
        return ToolSource.LOCAL;
    }

    /// Returns a JSON Schema string describing the tool's input parameters.
    /// The default is a flat {@code "query"} string schema.
    default String getInputSchemaJson() {
        return """
               {
                 "$schema": "https://json-schema.org/draft/2020-12/schema",
                 "type": "object",
                 "properties": {
                   "query": {
                     "type": "string",
                     "description": "Optional query or input for the tool"
                   }
                 },
                 "required": []
               }
               """;
    }

    /// Whether this tool provides a structured schema beyond the flat
    /// {@code "query"} fallback.  When {@code true}, LangChain4j adapters
    /// should use {@link #getInputSchemaJson()} to build a richer
    /// {@code ToolSpecification}.
    default boolean supportsStructuredSchema() {
        return false;
    }
}
