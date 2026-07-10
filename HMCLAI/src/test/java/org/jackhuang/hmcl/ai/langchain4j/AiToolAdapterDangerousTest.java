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
package org.jackhuang.hmcl.ai.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.jackhuang.hmcl.ai.AiApprovalMode;
import org.jackhuang.hmcl.ai.tools.AiExecutionPolicy;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolConfirmHandler;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSource;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks in the user-requested safety rule: a dangerous shell command (format / dd / fork bomb /
/// recursive delete) ALWAYS requires confirmation — even when the policy would otherwise
/// auto-allow it (dangerous confirmation disabled). The ONLY bypass is the developer "dangerously
/// skip permissions" toggle, modelled here as a null confirm handler (which is exactly how
/// AIMainPage wires it).
public final class AiToolAdapterDangerousTest {

    /// Mirrors the real {@link org.jackhuang.hmcl.ai.tools.ShellTool}'s shape — notably
    /// {@code getPermission() == DANGEROUS_WRITE} — rather than the plain {@link Tool} interface
    /// (which would default to {@code CONTROLLED_WRITE}). Several tests below rely on shell always
    /// resolving DANGEROUS_WRITE regardless of whether the command itself trips a dangerous/overlap
    /// pattern (e.g. a benign, non-overlapping command must still force a confirm to capture its
    /// summary) — a plain {@code Tool} stub would silently ALLOW those and never invoke the handler.
    private static final class StubShellTool implements ToolSpec {
        @Override public String getName() { return "shell"; }
        @Override public String getDescription() { return "run a shell command (test stub)"; }
        @Override public ToolPermission getPermission() { return ToolPermission.DANGEROUS_WRITE; }
        @Override public ToolSource getSource() { return ToolSource.LOCAL; }
        @Override public ToolResult execute(Map<String, Object> parameters) { return ToolResult.success("ran"); }
    }

    private static ToolRegistry registryWithShell() {
        ToolRegistry r = new ToolRegistry();
        r.register(new StubShellTool());
        return r;
    }

    /// Stands in for {@code McpToolStub}: an MCP-sourced tool resolving DANGEROUS_WRITE, the way
    /// McpToolStub.getPermission() now classifies every discovered MCP tool.
    private static final class StubMcpTool implements ToolSpec {
        @Override public String getName() { return "mcp.test-server.doThing"; }
        @Override public String getDescription() { return "[MCP:test-server] does a thing"; }
        @Override public ToolPermission getPermission() { return ToolPermission.DANGEROUS_WRITE; }
        @Override public ToolSource getSource() { return ToolSource.MCP; }
        @Override public ToolResult execute(Map<String, Object> parameters) { return ToolResult.success("mcp-ran"); }
    }

    private static ToolRegistry registryWithMcpTool() {
        ToolRegistry r = new ToolRegistry();
        r.register(new StubMcpTool());
        return r;
    }

    private static ToolExecutionRequest mcpReq() {
        return ToolExecutionRequest.builder().name("mcp.test-server.doThing").arguments("{}").build();
    }

    private static ToolExecutionRequest shellReq(String command) {
        return ToolExecutionRequest.builder()
                .name("shell")
                .arguments("{\"command\":\"" + command + "\"}")
                .build();
    }

    /// A lenient Auto policy (dangerous confirmation disabled) — used to prove the
    /// dangerousShell/MCP force-confirm gate fires independently of what the base policy decision
    /// would otherwise be, exactly as it used to under the old YOLO mode this replaced (see
    /// {@link AiApprovalMode}'s own doc for that merge).
    private static AiExecutionPolicy autoPolicy() {
        return new AiExecutionPolicy(AiApprovalMode.AUTO, false);
    }

    @Test
    void dangerousCommandForcesConfirmEvenWhenPolicyWouldAllow() {
        AtomicInteger confirms = new AtomicInteger();
        ToolConfirmHandler handler = (name, summary) -> { confirms.incrementAndGet(); return true; };
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registryWithShell(), autoPolicy(), handler, handler);

        ToolExecutionResultMessage result = adapter.execute(shellReq("format C:"));
        assertEquals(1, confirms.get(), "a dangerous command must prompt for confirmation even when the policy would allow it");
        assertTrue(result.text().contains("ran"), "a confirmed dangerous command should still run");
    }

    @Test
    void dangerousCommandViaInputAliasAlsoForcesConfirm() {
        AtomicInteger confirms = new AtomicInteger();
        ToolConfirmHandler handler = (name, summary) -> { confirms.incrementAndGet(); return true; };
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registryWithShell(), autoPolicy(), handler, handler);
        // ShellTool reads command/query/input; a dangerous command via the 'input' alias must NOT bypass the gate.
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .name("shell").arguments("{\"input\":\"format C:\"}").build();
        ToolExecutionResultMessage result = adapter.execute(req);
        assertEquals(1, confirms.get(), "a dangerous command via the 'input' alias must also prompt");
        assertTrue(result.text().contains("ran"));
    }

    @Test
    void presentButNullCommandKeyWithDangerousInputStillConfirms() {
        AtomicInteger confirms = new AtomicInteger();
        ToolConfirmHandler handler = (name, summary) -> { confirms.incrementAndGet(); return true; };
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registryWithShell(), autoPolicy(), handler, handler);
        // {"command":null,"input":"format C:"} — the gate must scan ALL aliases (not containsKey-chain),
        // matching ShellTool's null-coalescing, so a present-but-null command can't smuggle a dangerous input.
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .name("shell").arguments("{\"command\":null,\"input\":\"format C:\"}").build();
        ToolExecutionResultMessage result = adapter.execute(req);
        assertEquals(1, confirms.get(), "present-but-null command + dangerous input must still prompt");
    }

    @Test
    void decliningADangerousCommandBlocksIt() {
        ToolConfirmHandler deny = (name, summary) -> false;
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registryWithShell(), autoPolicy(), deny, deny);

        ToolExecutionResultMessage result = adapter.execute(shellReq("dd if=/dev/zero of=/dev/sda"));
        assertTrue(result.text().toLowerCase().contains("declined"),
                "a declined dangerous command must not run: " + result.text());
    }

    @Test
    void benignCommandDoesNotPromptUnderAuto() {
        AtomicInteger confirms = new AtomicInteger();
        ToolConfirmHandler handler = (name, summary) -> { confirms.incrementAndGet(); return true; };
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registryWithShell(), autoPolicy(), handler, handler);

        ToolExecutionResultMessage result = adapter.execute(shellReq("ls -la"));
        assertEquals(0, confirms.get(), "a benign command should not prompt");
        assertTrue(result.text().contains("ran"));
    }

    @Test
    void dangerouslySkipPermissionsBypassesTheConfirm() {
        // dangerouslySkipPermissions removes the confirm handler entirely (AIMainPage passes null) —
        // the only true bypass of the always-on dangerous-command gate.
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registryWithShell(), autoPolicy(), null, null);

        ToolExecutionResultMessage result = adapter.execute(shellReq("format C:"));
        assertTrue(result.text().contains("ran"),
                "with the handler removed (dangerously-skip), a dangerous command runs without a prompt");
    }

    // ---- MCP tools: same "always confirm, even when the policy would allow" guarantee as
    // dangerous shell commands ----
    //
    // An MCP tool is external, user-configured server code of unknown effect (see McpToolStub).
    // McpToolStub.getPermission() now resolves DANGEROUS_WRITE for every discovered MCP tool, and
    // LangChain4jToolAdapter.execute() force-confirms any MCP-sourced ToolSpec even when the policy
    // decision would otherwise be ALLOW (dangerous confirmation disabled) — mirroring the
    // dangerousShell mechanism above.

    @Test
    void mcpToolForcesConfirmEvenWhenPolicyWouldAllow() {
        AtomicInteger confirms = new AtomicInteger();
        ToolConfirmHandler handler = (name, summary) -> { confirms.incrementAndGet(); return true; };
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registryWithMcpTool(), autoPolicy(), handler, handler);

        ToolExecutionResultMessage result = adapter.execute(mcpReq());
        assertEquals(1, confirms.get(), "an MCP tool call must prompt for confirmation even when the policy would allow it");
        assertTrue(result.text().contains("mcp-ran"), "a confirmed MCP tool call should still run");
    }

    @Test
    void mcpToolAlsoConfirmsWithDangerousConfirmationEnabled() {
        AtomicInteger confirms = new AtomicInteger();
        ToolConfirmHandler handler = (name, summary) -> { confirms.incrementAndGet(); return true; };
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true);
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registryWithMcpTool(), policy, handler, handler);

        ToolExecutionResultMessage result = adapter.execute(mcpReq());
        assertEquals(1, confirms.get(), "an MCP tool call must prompt for confirmation under Auto");
        assertTrue(result.text().contains("mcp-ran"));
    }

    @Test
    void decliningAnMcpToolCallBlocksItEvenWhenPolicyWouldAllow() {
        ToolConfirmHandler deny = (name, summary) -> false;
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registryWithMcpTool(), autoPolicy(), deny, deny);

        ToolExecutionResultMessage result = adapter.execute(mcpReq());
        assertTrue(result.text().toLowerCase().contains("declined"),
                "a declined MCP tool call must not run: " + result.text());
    }

    @Test
    void mcpToolDangerouslySkipPermissionsBypassesTheConfirm() {
        // Same only-true-bypass as the shell case: AIMainPage passes a null confirm handler.
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registryWithMcpTool(), autoPolicy(), null, null);

        ToolExecutionResultMessage result = adapter.execute(mcpReq());
        assertTrue(result.text().contains("mcp-ran"),
                "with the handler removed (dangerously-skip), an MCP tool call runs without a prompt");
    }

    // ---- Unattended turns: dangerousShell/MCP calls resolve DANGEROUS_WRITE, so they are subject
    // to the same non-negotiable unattended BLOCK as any other dangerous operation (see
    // AiExecutionPolicy's class doc) — never merely asked, confirm handler or not. ----

    @Test
    void dangerousCommandIsBlockedNotAskedWhenTurnMayBeUnattended() {
        AtomicInteger confirms = new AtomicInteger();
        ToolConfirmHandler handler = (name, summary) -> { confirms.incrementAndGet(); return true; };
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, false); // even lenient
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(
                registryWithShell(), policy, handler, handler, null, null, () -> true);

        ToolExecutionResultMessage result = adapter.execute(shellReq("format C:"));
        assertEquals(0, confirms.get(), "an unattended dangerous command must be blocked outright, never asked");
        assertTrue(result.text().startsWith("Error:"));
        assertFalse(result.text().contains("ran"), "the command must not have executed");
    }

    @Test
    void mcpToolIsBlockedNotAskedWhenTurnMayBeUnattended() {
        AtomicInteger confirms = new AtomicInteger();
        ToolConfirmHandler handler = (name, summary) -> { confirms.incrementAndGet(); return true; };
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, false);
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(
                registryWithMcpTool(), policy, handler, handler, null, null, () -> true);

        ToolExecutionResultMessage result = adapter.execute(mcpReq());
        assertEquals(0, confirms.get(), "an unattended MCP call must be blocked outright, never asked");
        assertTrue(result.text().startsWith("Error:"));
        assertFalse(result.text().contains("mcp-ran"));
    }

    @Test
    void dangerouslySkipPermissionsOutranksTheUnattendedBlockToo() {
        // The developer-only escape hatch skips every gate, including the unattended block.
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true, true);
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(
                registryWithShell(), policy, null, null, null, null, () -> true);

        ToolExecutionResultMessage result = adapter.execute(shellReq("format C:"));
        assertTrue(result.text().contains("ran"));
    }

    // ---- Golden-rule overlap (ShellToolOverlap): a shell command that duplicates a dedicated
    // tool's job (e.g. downloading a mod jar straight from Modrinth/CurseForge with
    // Invoke-WebRequest/curl instead of instance(action="mods_install")) is a NUDGE, not a gate —
    // it never upgrades the permission or blocks the call, it only (a) prepends its reason to the
    // confirm summary the user sees and (b) forces that confirm even when the base policy would
    // otherwise auto-allow every shell call unconditionally (dangerous confirmation disabled,
    // formerly "YOLO" — see AiApprovalMode's own doc for that merge). A plain `curl ... -o mods\x.jar`
    // matches no DangerousCommands pattern at all, so without this it would only ever be gated by
    // ShellTool's own blanket DANGEROUS_WRITE classification — i.e. purely by luck. ----

    private static ToolExecutionRequest downloadIntoModsReq() {
        // Forward slashes (valid PowerShell path separators too) so the raw JSON built by shellReq()
        // doesn't need backslash escaping.
        return shellReq("Invoke-WebRequest -Uri https://cdn.modrinth.com/data/AANobbMI/versions/1.2.3/sodium.jar "
                + "-OutFile C:/Users/me/.minecraft/mods/sodium.jar");
    }

    @Test
    void shellDownloadOverlappingModsInstallForcesConfirmUnderYolo() {
        // "YOLO" today = Auto with dangerous confirmation disabled: ShellTool's blanket
        // DANGEROUS_WRITE would otherwise resolve straight to ALLOW, and this command trips no
        // DangerousCommands pattern (no delete verb, no format/dd/…) — only the overlap nudge below
        // forces the confirm.
        AtomicInteger confirms = new AtomicInteger();
        ToolConfirmHandler handler = (name, summary) -> { confirms.incrementAndGet(); return true; };
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registryWithShell(), autoPolicy(), handler, handler);

        ToolExecutionResultMessage result = adapter.execute(downloadIntoModsReq());
        assertEquals(1, confirms.get(),
                "a shell download overlapping a dedicated tool must force confirmation even under YOLO");
        assertTrue(result.text().contains("ran"), "a confirmed overlapping download should still run");
    }

    @Test
    void confirmSummaryIncludesTheOverlapReason() {
        AtomicReference<String> capturedSummary = new AtomicReference<>();
        ToolConfirmHandler handler = (name, summary) -> { capturedSummary.set(summary); return true; };
        // Dangerous confirmation ENABLED too: the base decision is already ASK (ShellTool is always
        // DANGEROUS_WRITE), so this proves the overlap reason is surfaced on that path as well, not
        // only on the force-confirm-under-YOLO path above.
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true);
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registryWithShell(), policy, handler, handler);

        adapter.execute(downloadIntoModsReq());
        String summary = capturedSummary.get();
        assertTrue(summary != null && summary.contains("专属工具"),
                "the confirm summary must surface the golden-rule overlap reason: " + summary);
    }

    @Test
    void decliningAnOverlappingDownloadBlocksIt() {
        ToolConfirmHandler deny = (name, summary) -> false;
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registryWithShell(), autoPolicy(), deny, deny);

        ToolExecutionResultMessage result = adapter.execute(downloadIntoModsReq());
        assertTrue(result.text().toLowerCase().contains("declined"),
                "a declined overlapping download must not run: " + result.text());
    }

    @Test
    void plainDownloadWithNoGamePathOrHostDoesNotAddTheOverlapReason() {
        // Unrelated to any dedicated tool (fetching a random file) — still forced to confirm because
        // ShellTool is always DANGEROUS_WRITE, but the summary must NOT claim a golden-rule overlap.
        // Dangerous confirmation must be ENABLED for that "always DANGEROUS_WRITE" premise to actually
        // force the confirm here: under the lenient autoPolicy() (dangerous confirmation disabled)
        // used elsewhere in this file, a DANGEROUS_WRITE call resolves straight to ALLOW unless
        // dangerousShell/mcpTool/overlap forces it — and this command trips none of those, so it
        // would silently ALLOW without ever invoking the handler, same as confirmSummaryIncludesTheOverlapReason
        // above.
        AtomicReference<String> capturedSummary = new AtomicReference<>();
        ToolConfirmHandler handler = (name, summary) -> { capturedSummary.set(summary); return true; };
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true);
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registryWithShell(), policy, handler, handler);

        adapter.execute(shellReq("curl https://example.com/changelog.txt -o changelog.txt"));
        String summary = capturedSummary.get();
        assertTrue(summary != null && !summary.contains("专属工具"),
                "a non-overlapping download must not carry the golden-rule overlap reason: " + summary);
    }

}
