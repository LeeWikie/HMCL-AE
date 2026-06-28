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
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for {@link LangChain4jToolAdapter} covering tool
/// specification building and tool execution delegation.
public final class LangChain4jToolAdapterTest {

    /// A minimal tool for testing.
    private static final class StubTool implements Tool {
        @Override
        public String getName() { return "stub-tool"; }

        @Override
        public String getDescription() { return "A stub for testing."; }

        @Override
        public ToolResult execute(Map<String, Object> parameters) {
            return ToolResult.success("stub-output");
        }
    }

    /// Verifies that an empty registry produces an empty specification list.
    @Test
    public void testBuildToolSpecificationsEmpty() {
        ToolRegistry registry = new ToolRegistry();
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registry);

        List<ToolSpecification> specs = adapter.buildToolSpecifications();
        assertTrue(specs.isEmpty());
    }

    /// Verifies that a single registered tool produces one specification
    /// with the correct name and description.
    @Test
    public void testBuildToolSpecificationsSingle() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool());
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registry);

        List<ToolSpecification> specs = adapter.buildToolSpecifications();
        assertEquals(1, specs.size());

        ToolSpecification spec = specs.get(0);
        assertEquals("stub-tool", spec.name());
        assertEquals("A stub for testing.", spec.description());
        assertNotNull(spec.parameters());
    }

    /// Verifies that multiple tools produce multiple specifications.
    @Test
    public void testBuildToolSpecificationsMultiple() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool());
        registry.register(new Tool() {
            @Override public String getName() { return "tool-2"; }
            @Override public String getDescription() { return "Second tool."; }
            @Override public ToolResult execute(Map<String, Object> p) {
                return ToolResult.success("ok");
            }
        });
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registry);

        List<ToolSpecification> specs = adapter.buildToolSpecifications();
        assertEquals(2, specs.size());
    }

    /// Verifies that executing a known tool request returns a result message.
    @Test
    public void testExecuteKnownTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool());
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registry);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("stub-tool")
                .arguments("{\"query\":\"test\"}")
                .build();

        ToolExecutionResultMessage result = adapter.execute(request);
        assertNotNull(result);
        assertEquals("stub-tool", result.toolName());
        assertEquals("stub-output", result.text());
    }

    /// Verifies that executing an unknown tool returns a non-null error
    /// result (never null) so the model receives a matching tool result and
    /// can self-correct, as required by the OpenAI/Anthropic APIs.
    @Test
    public void testExecuteUnknownTool() {
        ToolRegistry registry = new ToolRegistry();
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registry);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("nonexistent")
                .arguments("{}")
                .build();

        ToolExecutionResultMessage result = adapter.execute(request);
        assertNotNull(result);
        assertEquals("nonexistent", result.toolName());
        assertTrue(result.text().startsWith("Error:"));
        assertTrue(result.text().contains("not found"));
    }

    /// Verifies that a tool which throws is caught and surfaced to the model
    /// as a non-null "Error: ..." result rather than aborting the turn.
    @Test
    public void testExecuteThrowingTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String getName() { return "throwing-tool"; }
            @Override public String getDescription() { return "Always throws."; }
            @Override public ToolResult execute(Map<String, Object> p) {
                throw new IllegalStateException("kaboom");
            }
        });
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registry);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("throwing-tool")
                .arguments("{}")
                .build();

        ToolExecutionResultMessage result = adapter.execute(request);
        assertNotNull(result);
        assertTrue(result.text().startsWith("Error:"));
        assertTrue(result.text().contains("kaboom"));
    }

    /// Verifies that a tool that returns a failure produces a result
    /// message with an error prefix.
    @Test
    public void testExecuteFailureResult() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String getName() { return "failing-tool"; }
            @Override public String getDescription() { return "Always fails."; }
            @Override public ToolResult execute(Map<String, Object> p) {
                return ToolResult.failure("Something broke");
            }
        });
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registry);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("failing-tool")
                .arguments("{}")
                .build();

        ToolExecutionResultMessage result = adapter.execute(request);
        assertNotNull(result);
        assertTrue(result.text().startsWith("Error:"));
        assertTrue(result.text().contains("Something broke"));
    }
}
