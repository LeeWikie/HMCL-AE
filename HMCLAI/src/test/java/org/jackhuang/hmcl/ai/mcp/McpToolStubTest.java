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
package org.jackhuang.hmcl.ai.mcp;

import org.jackhuang.hmcl.ai.AiApprovalMode;
import org.jackhuang.hmcl.ai.tools.AiExecutionPolicy;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Locks in the MCP tool safety rule: an MCP tool is external, user-configured server code of
/// unknown effect (see {@link McpToolStub}'s own doc), so it must resolve to
/// {@link ToolPermission#DANGEROUS_WRITE} — the only permission {@link AiExecutionPolicy} confirms
/// by default under Auto (see {@link AiApprovalMode}'s own doc for the SAFE/ASK/YOLO merge this
/// replaced). (Never {@link ToolPermission#CONTROLLED_WRITE}: that level only asks when the user
/// has separately turned on the "file write confirm" toggle, which is off by default — meaning a
/// CONTROLLED_WRITE-classified MCP tool could silently auto-run.)
///
/// The client is irrelevant to permission/source resolution, so a {@code null} is passed — only
/// {@link McpToolStub#execute} would ever dereference it.
public final class McpToolStubTest {

    private static McpToolStub stub() {
        return new McpToolStub(null, "test-server", "doThing", "does a thing", null);
    }

    @Test
    void permissionIsDangerousWriteNotControlledWrite() {
        assertEquals(ToolPermission.DANGEROUS_WRITE, stub().getPermission(),
                "an MCP tool must never resolve to a permission level that AiExecutionPolicy can "
                        + "silently allow through under Auto by default");
    }

    @Test
    void sourceIsMcp() {
        assertEquals(ToolSource.MCP, stub().getSource());
    }

    @Test
    void policyRequiresConfirmationForDangerousWriteByDefault() {
        assertEquals(AiExecutionPolicy.Decision.ASK,
                new AiExecutionPolicy(AiApprovalMode.AUTO, true).check(ToolPermission.DANGEROUS_WRITE),
                "Auto must ask before running an MCP tool (dangerous confirmation defaults on)");
    }

    @Test
    void policyAloneAllowsDangerousWriteWhenConfirmationDisabledSoAdapterMustForceConfirmSeparately() {
        // Honest limitation: AiExecutionPolicy.check() auto-ALLOWs DANGEROUS_WRITE once the user has
        // turned the dangerous-confirmation toggle off — a DANGEROUS_WRITE classification is not, by
        // itself, enough to force a confirmation there. That's why LangChain4jToolAdapter.execute()
        // has a separate, policy-independent force-confirm gate for any MCP-sourced tool call
        // (mirroring its existing "dangerousShell" always-confirm mechanism) — see
        // AiToolAdapterDangerousTest.mcpToolForcesConfirmEvenWhenPolicyWouldAllow for that
        // end-to-end guarantee. (This does NOT apply while the turn may be unattended: that case
        // BLOCKs outright regardless of this toggle — see AiExecutionPolicy's class doc.)
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                new AiExecutionPolicy(AiApprovalMode.AUTO, false).check(ToolPermission.DANGEROUS_WRITE),
                "AiExecutionPolicy itself does not gate a disabled dangerous-confirmation toggle — the adapter-level gate does");
    }

    @Test
    void policyBlocksDangerousWriteOutrightWhenTurnMayBeUnattendedRegardlessOfTheToggle() {
        assertEquals(AiExecutionPolicy.Decision.BLOCK,
                new AiExecutionPolicy(AiApprovalMode.AUTO, false)
                        .check(null, null, ToolPermission.DANGEROUS_WRITE, false, true),
                "an unattended turn must BLOCK an MCP tool's DANGEROUS_WRITE call outright, even "
                        + "with dangerous confirmation disabled");
    }
}
