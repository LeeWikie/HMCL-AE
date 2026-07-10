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

import org.jackhuang.hmcl.ai.AiApprovalMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for the reason-carrying {@link AiExecutionPolicy.Verdict} (borrow-list A4): a BLOCK must
/// say WHICH gate fired — Plan Mode and unattended-dangerous demand different next actions from
/// the model — while {@code check(...)} stays a lossless delegate so no existing caller changes
/// behaviour.
public final class AiExecutionPolicyVerdictTest {

    private static AiExecutionPolicy policy() {
        return new AiExecutionPolicy(AiApprovalMode.AUTO, true, false);
    }

    @Test
    public void planModeBlockCarriesPlanModeReason() {
        AiExecutionPolicy.Verdict v = policy().evaluate(
                "instance", "create", ToolPermission.CONTROLLED_WRITE, true, false);
        assertEquals(AiExecutionPolicy.Decision.BLOCK, v.decision());
        assertEquals(AiExecutionPolicy.BlockReason.PLAN_MODE, v.blockReason());
    }

    @Test
    public void unattendedDangerousBlockCarriesUnattendedReason() {
        AiExecutionPolicy.Verdict v = policy().evaluate(
                "shell", null, ToolPermission.DANGEROUS_WRITE, false, true);
        assertEquals(AiExecutionPolicy.Decision.BLOCK, v.decision());
        assertEquals(AiExecutionPolicy.BlockReason.UNATTENDED_DANGEROUS, v.blockReason());
    }

    @Test
    public void planModeWinsWhenBothGatesWouldFire() {
        // Plan Mode is checked first (it also covers CONTROLLED_WRITE) — when both apply, the
        // reason must be PLAN_MODE, matching the evaluation order check() always had.
        AiExecutionPolicy.Verdict v = policy().evaluate(
                "shell", null, ToolPermission.DANGEROUS_WRITE, true, true);
        assertEquals(AiExecutionPolicy.Decision.BLOCK, v.decision());
        assertEquals(AiExecutionPolicy.BlockReason.PLAN_MODE, v.blockReason());
    }

    @Test
    public void allowAndAskCarryNoReason() {
        AiExecutionPolicy.Verdict allow = policy().evaluate(
                "read", null, ToolPermission.READ_ONLY, false, false);
        assertEquals(AiExecutionPolicy.Decision.ALLOW, allow.decision());
        assertNull(allow.blockReason());

        AiExecutionPolicy.Verdict ask = policy().evaluate(
                "shell", null, ToolPermission.DANGEROUS_WRITE, false, false);
        assertEquals(AiExecutionPolicy.Decision.ASK, ask.decision());
        assertNull(ask.blockReason());
    }

    @Test
    public void dangerouslySkipPermissionsStillBypassesEverything() {
        AiExecutionPolicy skip = new AiExecutionPolicy(AiApprovalMode.AUTO, true, true);
        AiExecutionPolicy.Verdict v = skip.evaluate(
                "shell", null, ToolPermission.DANGEROUS_WRITE, true, true);
        assertEquals(AiExecutionPolicy.Decision.ALLOW, v.decision());
        assertNull(v.blockReason());
    }

    @Test
    public void checkDelegatesToEvaluateAcrossTheWholeMatrix() {
        AiExecutionPolicy p = policy();
        for (ToolPermission perm : ToolPermission.values()) {
            for (boolean plan : new boolean[]{false, true}) {
                for (boolean unattended : new boolean[]{false, true}) {
                    assertEquals(p.evaluate("t", "a", perm, plan, unattended).decision(),
                            p.check("t", "a", perm, plan, unattended),
                            "check() must be a lossless delegate for perm=" + perm
                                    + " plan=" + plan + " unattended=" + unattended);
                }
            }
        }
    }
}
