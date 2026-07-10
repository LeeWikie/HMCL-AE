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
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.jackhuang.hmcl.ai.AiApprovalMode;
import org.jackhuang.hmcl.ai.tools.AiExecutionPolicy;
import org.jackhuang.hmcl.ai.tools.AiJobManager;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolRegistry;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Envelope-format regression lock for {@code LangChain4jToolAdapter}'s own failure texts
/// (the "6 文案点" wired to the {@link ToolFailures} factory), the per-reason BLOCK texts
/// (H3 / rewrite #21), and the background-dispatch truth check (H5 / rewrite #18).
public final class LangChain4jToolAdapterEnvelopeTest {

    private static void assertEnvelope(String text) {
        assertTrue(ToolFailures.isWellFormedEnvelope(text),
                "expected a well-formed failure envelope (Retryable: + Next:): " + text);
    }

    // ---- H3: per-reason BLOCK texts (rewrite #21) ----

    @Test
    public void planModeBlockTextIsPreciseAndEnveloped() {
        String text = LangChain4jToolAdapter.blockedText(AiExecutionPolicy.BlockReason.PLAN_MODE);
        assertTrue(text.startsWith("Error: blocked"), text);
        assertTrue(text.contains("Plan Mode"), "must name the actual gate: " + text);
        assertTrue(text.contains("read-only"), "must tell the model it can keep investigating: " + text);
        assertFalse(text.contains("unattended"), "must NOT mention the other cause: " + text);
        assertEnvelope(text);
    }

    @Test
    public void unattendedBlockTextIsPreciseAndEnveloped() {
        String text = LangChain4jToolAdapter.blockedText(AiExecutionPolicy.BlockReason.UNATTENDED_DANGEROUS);
        assertTrue(text.startsWith("Error: blocked"), text);
        assertTrue(text.contains("unattended"), "must name the actual gate: " + text);
        assertTrue(text.contains("end this turn"), "must tell the model to wrap up, not investigate: " + text);
        assertFalse(text.contains("Plan Mode"), "must NOT mention the other cause: " + text);
        assertEnvelope(text);
    }

    @Test
    public void unknownBlockReasonStillProducesAWellFormedEnvelope() {
        assertEnvelope(LangChain4jToolAdapter.blockedText(null));
    }

    /// Full-path check: a write call under Plan Mode comes back with the PLAN_MODE text.
    @Test
    public void executeUnderPlanModeReturnsThePlanModeEnvelope() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WriteStubTool());
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(
                registry, new AiExecutionPolicy(AiApprovalMode.AUTO, true, false),
                null, null, null, () -> true);

        ToolExecutionResultMessage result = adapter.execute(ToolExecutionRequest.builder()
                .name("stub").arguments("{\"action\":\"write\"}").build());
        assertTrue(result.text().contains("Plan Mode"), result.text());
        assertFalse(result.text().contains("unattended"), result.text());
        assertEnvelope(result.text());
    }

    /// Full-path check: an unattended dangerous call comes back with the UNATTENDED text.
    @Test
    public void executeUnattendedDangerousReturnsTheUnattendedEnvelope() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new DangerousStubTool());
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(
                registry, new AiExecutionPolicy(AiApprovalMode.AUTO, true, false),
                null, null, null, null, () -> true);

        ToolExecutionResultMessage result = adapter.execute(ToolExecutionRequest.builder()
                .name("danger").arguments("{}").build());
        assertTrue(result.text().contains("unattended"), result.text());
        assertFalse(result.text().contains("Plan Mode"), result.text());
        assertEnvelope(result.text());
    }

    /// Full-path check, YOLO edition: with Plan Mode off, `yolo`'s whole purpose is to auto-run
    /// dangerous operations while attended — but an unattended DANGEROUS_WRITE must still come back
    /// BLOCKed with the UNATTENDED text specifically (never the ALLOW an attended `yolo` call would
    /// get, and never mistaken for the Plan Mode gate, which isn't in play here).
    @Test
    public void executeUnattendedDangerousUnderYoloStillReturnsTheUnattendedEnvelope() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new DangerousStubTool());
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(
                registry, new AiExecutionPolicy(AiApprovalMode.YOLO, true, false),
                null, null, null, () -> false, () -> true);

        ToolExecutionResultMessage result = adapter.execute(ToolExecutionRequest.builder()
                .name("danger").arguments("{}").build());
        assertTrue(result.text().contains("unattended"), result.text());
        assertFalse(result.text().contains("Plan Mode"), result.text());
        assertEnvelope(result.text());
    }

    /// Full-path check, YOLO-parked-under-Plan edition: AIMainPage's composer popup lets a user
    /// pick `yolo` (the persisted AiApprovalMode) and THEN turn the separate Plan Mode boolean on
    /// via the popup's Plan row — {@code selectPlanMode()} deliberately leaves the parked mode
    /// untouched (see AIMainPage's own doc) — so it is entirely realistic for `yolo` to be parked
    /// underneath a UI showing "Plan" highlighted. Whatever the reason label ends up being (Plan
    /// Mode's own gate fires first and also covers DANGEROUS_WRITE — see
    /// AiExecutionPolicyVerdictTest#planModeWinsWhenBothGatesWouldFire), the decision reaching the
    /// model must be BLOCK, never ALLOW: the composer showing "Plan" instead of "yolo" must not be
    /// read as making the underlying judgment call any LESS safe than "yolo alone, unattended, Plan
    /// off" already is (locked down by the test above).
    @Test
    public void executeUnattendedDangerousUnderYoloWithPlanModeAlsoActiveStillBlocks() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new DangerousStubTool());
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(
                registry, new AiExecutionPolicy(AiApprovalMode.YOLO, true, false),
                null, null, null, () -> true, () -> true);

        ToolExecutionResultMessage result = adapter.execute(ToolExecutionRequest.builder()
                .name("danger").arguments("{}").build());
        assertTrue(result.text().contains("Error: blocked"), result.text());
        assertEnvelope(result.text());
    }

    // ---- factory-wired failure texts ----

    @Test
    public void userDeclinedTextIsTerminalAndEnveloped() {
        assertTrue(LangChain4jToolAdapter.USER_DECLINED_TEXT.startsWith("Error: the user declined"));
        assertTrue(LangChain4jToolAdapter.USER_DECLINED_TEXT.contains("Retryable: no"),
                "a user refusal is terminal");
        assertEnvelope(LangChain4jToolAdapter.USER_DECLINED_TEXT);
    }

    @Test
    public void unknownToolFailureIsEnveloped() {
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(new ToolRegistry());
        ToolExecutionResultMessage result = adapter.execute(ToolExecutionRequest.builder()
                .name("nonexistent").arguments("{}").build());
        assertTrue(result.text().startsWith("Error:"));
        assertTrue(result.text().contains("not found"));
        assertEnvelope(result.text());
    }

    @Test
    public void disabledToolFailureIsEnveloped() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WriteStubTool());
        registry.disable("stub");
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registry);
        ToolExecutionResultMessage result = adapter.execute(ToolExecutionRequest.builder()
                .name("stub").arguments("{}").build());
        assertTrue(result.text().contains("disabled"), result.text());
        assertEnvelope(result.text());
    }

    @Test
    public void throwingToolFailureKeepsTheMessageAndIsEnveloped() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String getName() { return "boom"; }
            @Override public String getDescription() { return "throws"; }
            @Override public ToolResult execute(Map<String, Object> p) {
                throw new IllegalStateException("kaboom");
            }
        });
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registry);
        ToolExecutionResultMessage result = adapter.execute(ToolExecutionRequest.builder()
                .name("boom").arguments("{}").build());
        assertTrue(result.text().contains("kaboom"), result.text());
        assertEnvelope(result.text());
    }

    @Test
    public void malformedArgumentsFailureIsEnvelopedAndEchoesTheSchema() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WriteStubTool());
        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(registry);
        ToolExecutionResultMessage result = adapter.execute(ToolExecutionRequest.builder()
                .name("stub").arguments("][ not json").build());
        assertTrue(result.text().startsWith("Error:"), result.text());
        assertTrue(result.text().contains("schema"), result.text());
        assertEnvelope(result.text());
    }

    // ---- H5: background dispatch tells the truth (rewrite #18) ----

    @Test
    public void backgroundDispatchFailedJobGetsAFailureEnvelopeNotASuccessClaim() {
        String text = LangChain4jToolAdapter.backgroundDispatchText(
                "instance", "7", AiJobManager.Status.FAILED, "Could not start background task: pool rejected");
        assertTrue(text.startsWith("Error:"), text);
        assertTrue(text.contains("could not be started"), text);
        assertTrue(text.contains("pool rejected"), "the job's own error must be carried: " + text);
        assertFalse(text.contains("已在后台开始执行"), "must NOT claim the task started: " + text);
        assertEnvelope(text);
    }

    @Test
    public void backgroundDispatchCancelledOrMissingJobAlsoFails() {
        assertEnvelope(LangChain4jToolAdapter.backgroundDispatchText(
                "instance", "8", AiJobManager.Status.CANCELLED, null));
        assertEnvelope(LangChain4jToolAdapter.backgroundDispatchText(
                "instance", "9", null, null));
    }

    @Test
    public void backgroundDispatchRunningJobKeepsTheOriginalReceipt() {
        String text = LangChain4jToolAdapter.backgroundDispatchText(
                "instance", "3", AiJobManager.Status.RUNNING, null);
        assertTrue(text.contains("已在后台开始执行（任务 #3：instance）"), text);
        assertTrue(text.contains("job(action=\"check\", jobId=\"3\")"), text);
        assertFalse(text.startsWith("Error:"), text);
    }

    @Test
    public void backgroundDispatchAlreadySucceededJobIsNotReportedAsAFailure() {
        // A very fast job can already be SUCCEEDED by the time we check — "started in the
        // background" is still accurate; job(action=check) remains the completion authority.
        String text = LangChain4jToolAdapter.backgroundDispatchText(
                "instance", "4", AiJobManager.Status.SUCCEEDED, null);
        assertTrue(text.contains("已在后台开始执行"), text);
        assertFalse(text.startsWith("Error:"), text);
    }

    // ---- stubs ----

    private static final class WriteStubTool implements ToolSpec {
        @Override public String getName() { return "stub"; }
        @Override public String getDescription() { return "controlled-write stub"; }
        @Override public ToolPermission getPermission(Map<String, Object> parameters) {
            return ToolPermission.CONTROLLED_WRITE;
        }
        @Override public boolean supportsStructuredSchema() { return false; }
        @Override public ToolResult execute(Map<String, Object> parameters) {
            return ToolResult.success("executed");
        }
    }

    private static final class DangerousStubTool implements ToolSpec {
        @Override public String getName() { return "danger"; }
        @Override public String getDescription() { return "dangerous stub"; }
        @Override public ToolPermission getPermission(Map<String, Object> parameters) {
            return ToolPermission.DANGEROUS_WRITE;
        }
        @Override public ToolResult execute(Map<String, Object> parameters) {
            return ToolResult.success("executed");
        }
    }
}
