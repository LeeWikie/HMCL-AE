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
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks in the user-requested safety rule: a dangerous shell command (format / dd / fork bomb /
/// recursive delete) ALWAYS requires confirmation — even in YOLO, where the policy otherwise
/// auto-allows. The ONLY bypass is the developer "dangerously skip permissions" toggle, modelled
/// here as a null confirm handler (which is exactly how AIMainPage wires it).
public final class AiToolAdapterDangerousTest {

    private static final class StubShellTool implements Tool {
        @Override public String getName() { return "shell"; }
        @Override public String getDescription() { return "run a shell command (test stub)"; }
        @Override public ToolResult execute(Map<String, Object> parameters) { return ToolResult.success("ran"); }
    }

    private static ToolRegistry registryWithShell() {
        ToolRegistry r = new ToolRegistry();
        r.register(new StubShellTool());
        return r;
    }

    private static ToolExecutionRequest shellReq(String command) {
        return ToolExecutionRequest.builder()
                .name("shell")
                .arguments("{\"command\":\"" + command + "\"}")
                .build();
    }

    private static AiExecutionPolicy yolo() {
        return new AiExecutionPolicy(AiApprovalMode.YOLO, false);
    }

    @Test
    void dangerousCommandForcesConfirmEvenInYolo() {
        AtomicInteger confirms = new AtomicInteger();
        ToolConfirmHandler handler = (name, summary) -> { confirms.incrementAndGet(); return true; };
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registryWithShell(), yolo(), handler, handler);

        ToolExecutionResultMessage result = adapter.execute(shellReq("format C:"));
        assertEquals(1, confirms.get(), "a dangerous command must prompt for confirmation even in YOLO");
        assertTrue(result.text().contains("ran"), "a confirmed dangerous command should still run");
    }

    @Test
    void decliningADangerousCommandBlocksIt() {
        ToolConfirmHandler deny = (name, summary) -> false;
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registryWithShell(), yolo(), deny, deny);

        ToolExecutionResultMessage result = adapter.execute(shellReq("dd if=/dev/zero of=/dev/sda"));
        assertTrue(result.text().toLowerCase().contains("declined"),
                "a declined dangerous command must not run: " + result.text());
    }

    @Test
    void benignCommandDoesNotPromptInYolo() {
        AtomicInteger confirms = new AtomicInteger();
        ToolConfirmHandler handler = (name, summary) -> { confirms.incrementAndGet(); return true; };
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registryWithShell(), yolo(), handler, handler);

        ToolExecutionResultMessage result = adapter.execute(shellReq("ls -la"));
        assertEquals(0, confirms.get(), "a benign command should not prompt in YOLO");
        assertTrue(result.text().contains("ran"));
    }

    @Test
    void dangerouslySkipPermissionsBypassesTheConfirm() {
        // dangerouslySkipPermissions removes the confirm handler entirely (AIMainPage passes null) —
        // the only true bypass of the always-on dangerous-command gate.
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registryWithShell(), yolo(), null, null);

        ToolExecutionResultMessage result = adapter.execute(shellReq("format C:"));
        assertTrue(result.text().contains("ran"),
                "with the handler removed (dangerously-skip), a dangerous command runs without a prompt");
    }
}
