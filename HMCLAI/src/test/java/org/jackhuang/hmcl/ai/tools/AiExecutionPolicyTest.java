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
import org.jackhuang.hmcl.ai.tools.AiExecutionPolicy.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for {@link AiExecutionPolicy}, covering the full decision matrix
/// of approval mode x tool permission, with special attention to the
/// {@code fileWriteConfirmEnabled} and {@code dangerousConfirmationEnabled} flags.
public final class AiExecutionPolicyTest {

    // ---- YOLO mode: always ALLOW regardless of flags ----

    @Test
    public void testYoloAlwaysAllows() {
        // Both confirmation flags on — YOLO still allows everything.
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.YOLO, true, true);
        for (ToolPermission permission : ToolPermission.values()) {
            assertEquals(Decision.ALLOW, policy.check(permission),
                    "YOLO must ALLOW " + permission + " even with confirmations enabled");
        }
    }

    // ---- READ_ONLY and EXTERNAL_NETWORK: always ALLOW in every mode ----

    @Test
    public void testReadOnlyAndNetworkAlwaysAllowed() {
        for (AiApprovalMode mode : AiApprovalMode.values()) {
            // Confirmation flags should never affect read-only / network permissions.
            AiExecutionPolicy policy = new AiExecutionPolicy(mode, true, true);
            assertEquals(Decision.ALLOW, policy.check(ToolPermission.READ_ONLY),
                    "READ_ONLY must ALLOW in mode " + mode);
            assertEquals(Decision.ALLOW, policy.check(ToolPermission.EXTERNAL_NETWORK),
                    "EXTERNAL_NETWORK must ALLOW in mode " + mode);
        }
    }

    // ---- DANGEROUS_WRITE driven by dangerousConfirmationEnabled (SAFE & ASK) ----

    @Test
    public void testDangerousWriteAsksWhenConfirmationEnabled() {
        for (AiApprovalMode mode : new AiApprovalMode[]{AiApprovalMode.SAFE, AiApprovalMode.ASK}) {
            AiExecutionPolicy policy = new AiExecutionPolicy(mode, true, false);
            assertEquals(Decision.ASK, policy.check(ToolPermission.DANGEROUS_WRITE),
                    "DANGEROUS_WRITE must ASK in mode " + mode + " when confirmation enabled");
        }
    }

    @Test
    public void testDangerousWriteAllowsWhenConfirmationDisabled() {
        for (AiApprovalMode mode : new AiApprovalMode[]{AiApprovalMode.SAFE, AiApprovalMode.ASK}) {
            AiExecutionPolicy policy = new AiExecutionPolicy(mode, false, false);
            assertEquals(Decision.ALLOW, policy.check(ToolPermission.DANGEROUS_WRITE),
                    "DANGEROUS_WRITE must ALLOW in mode " + mode + " when confirmation disabled");
        }
    }

    // ---- CONTROLLED_WRITE driven by fileWriteConfirmEnabled ----

    @Test
    public void testControlledWriteAllowsWhenFileWriteConfirmDisabled() {
        // Default (fileWriteConfirmEnabled = false): controlled writes run freely.
        for (AiApprovalMode mode : new AiApprovalMode[]{AiApprovalMode.SAFE, AiApprovalMode.ASK}) {
            AiExecutionPolicy policy = new AiExecutionPolicy(mode, true, false);
            assertEquals(Decision.ALLOW, policy.check(ToolPermission.CONTROLLED_WRITE),
                    "CONTROLLED_WRITE must ALLOW in mode " + mode + " when fileWriteConfirm disabled");
        }
    }

    @Test
    public void testControlledWriteAsksWhenFileWriteConfirmEnabledInSafe() {
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.SAFE, true, true);
        assertEquals(Decision.ASK, policy.check(ToolPermission.CONTROLLED_WRITE),
                "CONTROLLED_WRITE must ASK in SAFE when fileWriteConfirm enabled");
    }

    @Test
    public void testControlledWriteAsksWhenFileWriteConfirmEnabledInAsk() {
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.ASK, true, true);
        assertEquals(Decision.ASK, policy.check(ToolPermission.CONTROLLED_WRITE),
                "CONTROLLED_WRITE must ASK in ASK when fileWriteConfirm enabled");
    }

    @Test
    public void testControlledWriteAllowsInYoloEvenWithFileWriteConfirmEnabled() {
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.YOLO, true, true);
        assertEquals(Decision.ALLOW, policy.check(ToolPermission.CONTROLLED_WRITE),
                "CONTROLLED_WRITE must ALLOW in YOLO regardless of fileWriteConfirm");
    }

    // ---- Constructors / defaults ----

    @Test
    public void testTwoArgConstructorDefaultsFileWriteConfirmToFalse() {
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.SAFE, true);
        assertFalse(policy.isFileWriteConfirmEnabled());
        assertEquals(Decision.ALLOW, policy.check(ToolPermission.CONTROLLED_WRITE));
    }

    @Test
    public void testDefaultConstructor() {
        AiExecutionPolicy policy = new AiExecutionPolicy();
        assertEquals(AiApprovalMode.SAFE, policy.getMode());
        assertTrue(policy.isDangerousConfirmationEnabled());
        assertFalse(policy.isFileWriteConfirmEnabled());
        // Default: dangerous writes are confirmed, controlled writes run freely.
        assertEquals(Decision.ASK, policy.check(ToolPermission.DANGEROUS_WRITE));
        assertEquals(Decision.ALLOW, policy.check(ToolPermission.CONTROLLED_WRITE));
        assertEquals(Decision.ALLOW, policy.check(ToolPermission.READ_ONLY));
    }

    @Test
    public void testAccessors() {
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.ASK, false, true);
        assertEquals(AiApprovalMode.ASK, policy.getMode());
        assertFalse(policy.isDangerousConfirmationEnabled());
        assertTrue(policy.isFileWriteConfirmEnabled());
    }
}
