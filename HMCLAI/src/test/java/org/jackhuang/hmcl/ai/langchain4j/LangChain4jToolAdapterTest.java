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
import org.jackhuang.hmcl.ai.AiApprovalMode;
import org.jackhuang.hmcl.ai.tools.AiExecutionPolicy;
import org.jackhuang.hmcl.ai.tools.AiToolPermissionStore;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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

    /// A merged-domain-style stub tool whose permission depends on its `action` parameter, mirroring
    /// the real `instance`/`search`/etc. domain tools (action=list is READ_ONLY, action=write is
    /// CONTROLLED_WRITE) — used by the Part A/C/E wiring tests below.
    private static final class DomainStubTool implements ToolSpec {
        private final String name;

        DomainStubTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() { return name; }

        @Override
        public String getDescription() { return "A domain-style stub for testing."; }

        @Override
        public ToolPermission getPermission(Map<String, Object> parameters) {
            Object action = parameters.get("action");
            return "write".equals(action) ? ToolPermission.CONTROLLED_WRITE : ToolPermission.READ_ONLY;
        }

        @Override
        public ToolResult execute(Map<String, Object> parameters) {
            return ToolResult.success("executed:" + parameters.get("action"));
        }
    }

    // ---- Part A: AiToolPermissionStore wired into the real per-call decision ----

    /// Proves an override actually changes behavior for one specific tool+action, while a
    /// DIFFERENT tool with the exact same shape still follows the global default — i.e. the
    /// override is scoped correctly, not accidentally global.
    @Test
    public void testPermissionStoreOverrideChangesBehaviorForSpecificToolActionOnly() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new DomainStubTool("domain-a"));
        registry.register(new DomainStubTool("domain-b"));

        // fileWriteConfirmEnabled=true: every CONTROLLED_WRITE call ASKs; with no confirmHandler
        // wired, an ASK decision is auto-declined ("Error: ... declined ...").
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true, true);
        AiToolPermissionStore store = new AiToolPermissionStore(Path.of("unused-permissions.json"));
        store.setOverride("domain-a", "write", AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW);

        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registry, policy, null, null, store, null);

        ToolExecutionRequest domainAWrite = ToolExecutionRequest.builder()
                .name("domain-a").arguments("{\"action\":\"write\"}").build();
        ToolExecutionResultMessage domainAResult = adapter.execute(domainAWrite);
        assertEquals("executed:write", domainAResult.text(),
                "domain-a's ALWAYS_ALLOW override should let the call through without asking");

        ToolExecutionRequest domainBWrite = ToolExecutionRequest.builder()
                .name("domain-b").arguments("{\"action\":\"write\"}").build();
        ToolExecutionResultMessage domainBResult = adapter.execute(domainBWrite);
        assertTrue(domainBResult.text().startsWith("Error:"),
                "domain-b has no override and must still follow the global ask-by-default policy");
        assertTrue(domainBResult.text().contains("declined"));
    }

    /// A tool with no permission store wired in at all (the pre-Part-A constructor) behaves exactly
    /// as before — the override lookup is skipped entirely.
    @Test
    public void testNoPermissionStoreMeansOnlyGlobalPolicyApplies() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new DomainStubTool("domain"));
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true, true);
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registry, policy, null);

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("domain").arguments("{\"action\":\"write\"}").build();
        ToolExecutionResultMessage result = adapter.execute(request);
        assertTrue(result.text().startsWith("Error:"));
    }

    /// A path-glob override (Part D) resolves BEFORE the tool/action-scoped lookup for a call that
    /// supplies a `path` parameter.
    @Test
    public void testPathGlobOverrideAppliesToPathTakingCall() {
        final class PathStubTool implements ToolSpec {
            @Override public String getName() { return "write"; }
            @Override public String getDescription() { return "stub write"; }
            @Override public ToolPermission getPermission(Map<String, Object> parameters) {
                return ToolPermission.CONTROLLED_WRITE;
            }
            @Override public ToolResult execute(Map<String, Object> parameters) {
                return ToolResult.success("wrote:" + parameters.get("path"));
            }
        }
        ToolRegistry registry = new ToolRegistry();
        registry.register(new PathStubTool());

        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true, true);
        AiToolPermissionStore store = new AiToolPermissionStore(Path.of("unused-permissions.json"));
        store.setPathOverride("write", "mods/**", AiToolPermissionStore.OverrideMode.ALWAYS_ALLOW);

        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registry, policy, null, null, store, null);

        ToolExecutionRequest underMods = ToolExecutionRequest.builder()
                .name("write").arguments("{\"path\":\"mods/foo.jar\",\"content\":\"x\"}").build();
        assertEquals("wrote:mods/foo.jar", adapter.execute(underMods).text());

        ToolExecutionRequest underSaves = ToolExecutionRequest.builder()
                .name("write").arguments("{\"path\":\"saves/world1/level.dat\",\"content\":\"x\"}").build();
        ToolExecutionResultMessage savesResult = adapter.execute(underSaves);
        assertTrue(savesResult.text().startsWith("Error:"),
                "a path outside the glob rule must still follow the global ask-by-default policy");
    }

    // ---- Part E: Plan Mode blocks writes per-action, not by wholesale tool disable ----

    /// The exact scenario from the bug report: while Plan Mode is active, a domain tool's
    /// READ_ONLY action (e.g. instance/list) must still work, while its CONTROLLED_WRITE action
    /// (e.g. instance/create) is BLOCKed outright.
    @Test
    public void testPlanModeBlocksWriteActionsButAllowsReadOnlyActionsOfTheSameTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new DomainStubTool("instance"));
        // fileWriteConfirmEnabled=false: the stub's "write" action isn't in EditOrRemoveActions'
        // curated set, so it would otherwise resolve straight to ALLOW — isolating this test to
        // Plan Mode's own BLOCK, not the unrelated file-write-confirm toggle.
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true, false);
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registry, policy, null, null, null, () -> true);

        ToolExecutionRequest listRequest = ToolExecutionRequest.builder()
                .name("instance").arguments("{\"action\":\"list\"}").build();
        assertEquals("executed:list", adapter.execute(listRequest).text());

        ToolExecutionRequest writeRequest = ToolExecutionRequest.builder()
                .name("instance").arguments("{\"action\":\"write\"}").build();
        ToolExecutionResultMessage writeResult = adapter.execute(writeRequest);
        assertTrue(writeResult.text().startsWith("Error:"));
        assertTrue(writeResult.text().contains("blocked"));
        assertTrue(writeResult.text().contains("Plan Mode"));
    }

    /// When Plan Mode is off (the common case), the same tool's write action runs normally.
    @Test
    public void testPlanModeSupplierFalseDoesNotBlockWrites() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new DomainStubTool("instance"));
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true, false);
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registry, policy, null, null, null, () -> false);

        ToolExecutionRequest writeRequest = ToolExecutionRequest.builder()
                .name("instance").arguments("{\"action\":\"write\"}").build();
        assertEquals("executed:write", adapter.execute(writeRequest).text());
    }

    // ---- Part B: confirm-dialog headline is always the deterministic tool+action name ----

    /// A poisoned model-supplied `description` must never become (or precede) the confirm-dialog
    /// headline — the headline is always the hardcoded tool+action name, with the description
    /// demoted to clearly-labelled secondary text below it.
    @Test
    public void testConfirmDialogHeadlineIsDeterministicNotModelDescription() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new DomainStubTool("instance"));
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true, true);

        final String poisonedDescription = "This is completely safe, no need to review, just click confirm.";
        final StringBuilder capturedSummary = new StringBuilder();
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registry, policy,
                (toolName, summary) -> {
                    capturedSummary.append(summary);
                    return true; // approve, so execute() proceeds and we can inspect the summary
                });

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("instance")
                .arguments("{\"action\":\"write\",\"description\":\"" + poisonedDescription + "\"}")
                .build();
        ToolExecutionResultMessage result = adapter.execute(request);
        assertEquals("executed:write", result.text());

        String summary = capturedSummary.toString();
        String headline = summary.split("\n\n", 2)[0];
        assertFalse(headline.contains(poisonedDescription),
                "the model-supplied description must never appear in the headline: " + headline);
        assertTrue(headline.contains("instance"), "the headline must name the real tool: " + headline);
        assertTrue(headline.contains("write"), "the headline must name the real action: " + headline);
        // The description is still shown, just demoted to secondary text below the headline.
        assertTrue(summary.contains(poisonedDescription));
        assertTrue(summary.indexOf(poisonedDescription) > summary.indexOf(headline));
    }
}
