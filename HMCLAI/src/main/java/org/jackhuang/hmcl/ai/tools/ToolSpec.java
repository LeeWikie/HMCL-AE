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

import java.util.Map;

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

    /// Action-aware permission: domain tools whose {@code action} parameter spans multiple
    /// permission levels (e.g. a merged `instance` tool where `list` is read-only but `delete` is
    /// dangerous) override this instead of — or in addition to — the no-arg {@link #getPermission()}.
    /// The default just ignores {@code parameters} and defers to the no-arg overload, so every
    /// existing single-permission tool needs no change.
    default ToolPermission getPermission(Map<String, Object> parameters) {
        return getPermission();
    }

    /// The tool's WORST-CASE (highest-risk) permission across every action it can resolve to via
    /// {@link #getPermission(Map)} — used by the settings/catalog UI ({@code ToolRegistry#getPermission},
    /// {@code AiToolCatalog}) to display a single risk level for a merged domain tool without
    /// silently understating it. The default just defers to the no-arg {@link #getPermission()},
    /// so a single-permission tool needs no change; a domain tool whose
    /// {@link #getPermission(Map)} override spans multiple levels (e.g. `instance`: `list` is
    /// READ_ONLY but `delete` is DANGEROUS_WRITE) should override this to return its actual
    /// maximum instead of leaving the settings page to report the conservative no-arg default.
    default ToolPermission getMaxPermission() {
        return getPermission();
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
