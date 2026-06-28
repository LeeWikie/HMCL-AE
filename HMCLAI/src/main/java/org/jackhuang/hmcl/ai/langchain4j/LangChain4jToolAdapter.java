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
package org.jackhuang.hmcl.ai.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/// Adapts HMCL's [`Tool`][org.jackhuang.hmcl.ai.tools.Tool] abstraction
/// into LangChain4j's [`ToolSpecification`] and
/// [`ToolExecutionResultMessage`] types so that native tool-calling in
/// LangChain4j models can invoke HMCL-registered tools.
///
/// Each HMCL tool is mapped to a LangChain4j tool specification whose
/// parameter schema is a single optional `"query"` string property.
/// When a tool execution request is received, the adapter looks up the
/// corresponding HMCL tool in the registry and passes the request arguments
/// as a parameter map.
@NotNullByDefault
public final class LangChain4jToolAdapter {

    private final ToolRegistry registry;

    /// Creates an adapter backed by the given tool registry.
    ///
    /// @param registry the HMCL tool registry; must not be {@code null}
    public LangChain4jToolAdapter(ToolRegistry registry) {
        this.registry = registry;
    }

    /// Builds LangChain4j [`ToolSpecification`] instances for all non-disabled
    /// tools in the HMCL tool registry.
    ///
    /// Tools that implement {@link ToolSpec} and support structured schema
    /// contribute richer parameter info.  All others use the legacy flat
    /// {@code "query"} parameter.
    ///
    /// @return an unmodifiable list of tool specifications
    public List<ToolSpecification> buildToolSpecifications() {
        List<Tool> tools = registry.list();
        if (tools.isEmpty()) {
            return Collections.emptyList();
        }
        List<ToolSpecification> specs = new ArrayList<>(tools.size());
        for (Tool tool : tools) {
            ToolSpecification.Builder builder = ToolSpecification.builder()
                    .name(tool.getName())
                    .description(tool.getDescription());
            JsonObjectSchema schema = null;
            if (tool instanceof ToolSpec spec && spec.supportsStructuredSchema()) {
                schema = parseSchema(spec.getInputSchemaJson());
            }
            builder.parameters(schema != null ? schema : JsonObjectSchema.builder()
                    .addStringProperty("query", "An optional query or input for the tool")
                    .build());
            specs.add(builder.build());
        }
        return Collections.unmodifiableList(specs);
    }

    /// Parses a (flat) JSON Schema string into a LangChain4j [`JsonObjectSchema`],
    /// mapping each `properties` entry to a typed property. Returns null on failure so
    /// the caller can fall back to the flat `"query"` schema.
    @Nullable
    @SuppressWarnings("unchecked")
    private static JsonObjectSchema parseSchema(String json) {
        try {
            Map<String, Object> root = GSON.fromJson(json, MAP_TYPE);
            if (root == null || !(root.get("properties") instanceof Map<?, ?> props) || props.isEmpty()) {
                return null;
            }
            JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) props).entrySet()) {
                String name = entry.getKey();
                String type = "string";
                String description = "";
                if (entry.getValue() instanceof Map<?, ?> meta) {
                    if (meta.get("type") != null) type = meta.get("type").toString();
                    if (meta.get("description") != null) description = meta.get("description").toString();
                }
                switch (type) {
                    case "integer" -> builder.addIntegerProperty(name, description);
                    case "number" -> builder.addNumberProperty(name, description);
                    case "boolean" -> builder.addBooleanProperty(name, description);
                    default -> builder.addStringProperty(name, description);
                }
            }
            return builder.build();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /// Executes a LangChain4j tool execution request by delegating to the
    /// corresponding HMCL tool.
    ///
    /// The request's `arguments()` JSON string is parsed into a simple
    /// parameter map and passed to {@link Tool#execute}. The result is
    /// wrapped in a LangChain4j
    /// [`ToolExecutionResultMessage`].
    ///
    /// @param request the tool execution request from the model
    /// @return a result message to inject into the conversation context,
    ///         or {@code null} if the tool is not found
    @Nullable
    public ToolExecutionResultMessage execute(ToolExecutionRequest request) {
        Tool tool = registry.get(request.name());
        if (tool == null) {
            return null;
        }

        Map<String, Object> parameters = parseArguments(request.arguments());
        ToolResult result = tool.execute(parameters);

        String text = result.isSuccess()
                ? result.getOutput()
                : "Error: " + result.getError();

        return ToolExecutionResultMessage.from(request, text);
    }

    /// Parses a JSON arguments string into a flat parameter map.
    ///
    /// This implementation returns an empty map, simplifying parameter
    /// passing for the MVP. Future iterations may implement full JSON
    /// deserialization.
    ///
    /// @param argumentsJson the JSON string from the tool execution request
    /// @return a parameter map; never {@code null}
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
    private static final java.lang.reflect.Type MAP_TYPE =
            new com.google.gson.reflect.TypeToken<Map<String, Object>>(){}.getType();

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> result = GSON.fromJson(argumentsJson, MAP_TYPE);
            return result != null ? result : Collections.emptyMap();
        } catch (com.google.gson.JsonParseException e) {
            return Collections.singletonMap("query", argumentsJson);
        }
    }
}
