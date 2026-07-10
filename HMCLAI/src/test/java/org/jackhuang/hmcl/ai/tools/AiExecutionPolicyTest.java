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

/// Unit tests for {@link AiExecutionPolicy}, covering:
/// - The Auto model (post SAFE/ASK/YOLO merge — see {@link AiApprovalMode}'s own doc): everyday
///   operations stay low-friction, dangerous operations ask while attended, and a dangerous
///   operation is hard-BLOCKed — never merely asked — whenever the turn may be unattended.
/// - Part C: the create-vs-edit/remove split ({@link EditOrRemoveActions}) — file-write
///   confirmation is policy-decided (no user toggle anymore): pure creation runs automatically,
///   an action that edits or removes something that already existed always asks.
/// - Part E: Plan Mode BLOCKing CONTROLLED_WRITE/DANGEROUS_WRITE outright (never merely asking),
///   while READ_ONLY/EXTERNAL_NETWORK calls are unaffected — the fix for
///   {@code AIMainPage.applyPlanGating()}'s wholesale over-blocking of the 6 merged domain facades
///   (instance/game/search/account/nbt/job), whose READ_ONLY actions must stay usable.
public final class AiExecutionPolicyTest {

    // ---- Auto model: low friction for everyday ops, ask-or-block for dangerous ones ----

    @Test
    void autoAllowsReadOnlyControlledWriteAndNetworkWithoutAsking() {
        // Default flags (dangerous confirmation on) — everyday operations must never be gated,
        // regardless of that toggle.
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true);
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                policy.check("read", null, ToolPermission.READ_ONLY, false, false));
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                policy.check("web_search", null, ToolPermission.EXTERNAL_NETWORK, false, false));
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                policy.check("write", "create", ToolPermission.CONTROLLED_WRITE, false, false));
    }

    @Test
    void autoAsksForDangerousWriteWhileAttended() {
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true);
        assertEquals(AiExecutionPolicy.Decision.ASK,
                policy.check("shell", null, ToolPermission.DANGEROUS_WRITE, false, false));
    }

    @Test
    void autoAllowsDangerousWriteWhileAttendedIfConfirmationToggleIsOff() {
        // The dangerous-confirmation toggle can still relax Auto down to a full auto-run for
        // dangerous operations — but only while attended (see the next test).
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, false);
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                policy.check("shell", null, ToolPermission.DANGEROUS_WRITE, false, false));
    }

    @Test
    void autoBlocksDangerousWriteWhenPossiblyUnattendedRegardlessOfConfirmationToggle() {
        // The load-bearing safety fix: even with dangerousConfirmationEnabled=false (which would
        // otherwise auto-allow, per the test above), a possibly-unattended turn hard-BLOCKs a
        // dangerous operation outright instead of silently running it or leaving it stuck on a
        // prompt nobody may ever answer.
        AiExecutionPolicy lenient = new AiExecutionPolicy(AiApprovalMode.AUTO, false);
        assertEquals(AiExecutionPolicy.Decision.BLOCK,
                lenient.check("shell", null, ToolPermission.DANGEROUS_WRITE, false, true));

        // Same result even when dangerous confirmation IS on — unattended never merely downgrades
        // to ASK, which nobody may be present to answer.
        AiExecutionPolicy strict = new AiExecutionPolicy(AiApprovalMode.AUTO, true);
        assertEquals(AiExecutionPolicy.Decision.BLOCK,
                strict.check("shell", null, ToolPermission.DANGEROUS_WRITE, false, true));
    }

    @Test
    void unattendedDoesNotBlockNonDangerousOperations() {
        // Only DANGEROUS_WRITE is gated by the unattended signal — read-only/network/controlled
        // writes keep running automatically even on a possibly-unattended turn (e.g. the agent
        // polling a background job's status after firing an auto-continuation).
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true);
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                policy.check("read", null, ToolPermission.READ_ONLY, false, true));
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                policy.check("web_search", null, ToolPermission.EXTERNAL_NETWORK, false, true));
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                policy.check("write", "create", ToolPermission.CONTROLLED_WRITE, false, true));
    }

    @Test
    void dangerouslySkipPermissionsOutranksTheUnattendedBlock() {
        // The developer-only escape hatch is documented to skip every gate, INCLUDING the
        // unattended block (and Plan Mode — see below).
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true, true);
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                policy.check("shell", null, ToolPermission.DANGEROUS_WRITE, false, true));
    }

    // ---- Part E: Plan Mode ----

    @Test
    void planModeBlocksControlledWriteRegardlessOfApprovalMode() {
        for (AiApprovalMode mode : AiApprovalMode.values()) {
            AiExecutionPolicy policy = new AiExecutionPolicy(mode, true);
            assertEquals(AiExecutionPolicy.Decision.BLOCK,
                    policy.check("instance", "create", ToolPermission.CONTROLLED_WRITE, true),
                    "Plan Mode must BLOCK a CONTROLLED_WRITE call under " + mode);
        }
    }

    @Test
    void planModeBlocksDangerousWriteRegardlessOfApprovalMode() {
        for (AiApprovalMode mode : AiApprovalMode.values()) {
            AiExecutionPolicy policy = new AiExecutionPolicy(mode, true);
            assertEquals(AiExecutionPolicy.Decision.BLOCK,
                    policy.check("instance", "delete", ToolPermission.DANGEROUS_WRITE, true),
                    "Plan Mode must BLOCK a DANGEROUS_WRITE call under " + mode);
        }
    }

    @Test
    void planModeDoesNotBlockReadOnlyActions() {
        // This is the exact scenario the bug report calls out: instance(action=list) and
        // search(action=mods) must stay usable in Plan Mode so the agent can keep investigating.
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true);
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                policy.check("instance", "list", ToolPermission.READ_ONLY, true));
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                policy.check("search", "mods", ToolPermission.READ_ONLY, true));
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                policy.check("nbt", "get", ToolPermission.READ_ONLY, true));
    }

    @Test
    void planModeDoesNotBlockExternalNetwork() {
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true);
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                policy.check("web_search", null, ToolPermission.EXTERNAL_NETWORK, true));
    }

    @Test
    void planModeStillBlocksWriteActionsOfTheSameDomainFacadeThatHasReadOnlyActionsToo() {
        // The core of the fix: `instance` is READ_ONLY for action=list but CONTROLLED_WRITE for
        // action=create — Plan Mode must let ONE through and BLOCK the OTHER, not treat the whole
        // tool as either all-allowed or all-disabled.
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true);
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                policy.check("instance", "list", ToolPermission.READ_ONLY, true));
        assertEquals(AiExecutionPolicy.Decision.BLOCK,
                policy.check("instance", "create", ToolPermission.CONTROLLED_WRITE, true));
        assertEquals(AiExecutionPolicy.Decision.BLOCK,
                policy.check("instance", "mods_install", ToolPermission.CONTROLLED_WRITE, true));
    }

    @Test
    void dangerouslySkipPermissionsOutranksPlanMode() {
        // The developer-only escape hatch is documented to skip every gate, INCLUDING Plan Mode.
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true, true);
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                policy.check("instance", "delete", ToolPermission.DANGEROUS_WRITE, true));
    }

    @Test
    void noArgCheckOverloadNeverAppliesPlanModeBlocking() {
        // Preserves the exact prior behavior of the simple, tool-agnostic overload (used by callers
        // with no tool/action context) — it must never resolve into a Plan Mode block, and (with
        // dangerous confirmation off, so nothing else gates it either) always ALLOWs.
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, false);
        assertEquals(AiExecutionPolicy.Decision.ALLOW, policy.check(ToolPermission.CONTROLLED_WRITE));
        assertEquals(AiExecutionPolicy.Decision.ALLOW, policy.check(ToolPermission.DANGEROUS_WRITE));
    }

    // ---- Part C: create vs edit/remove ----

    @Test
    void createTypeControlledWriteRunsAutomatically() {
        // Pure creation is policy-decided to run without confirmation (the old
        // fileWriteConfirmEnabled toggle is gone — this is now the unconditional behavior).
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true);
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                policy.check("instance", "create", ToolPermission.CONTROLLED_WRITE, false));
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                policy.check("instance", "mods_install", ToolPermission.CONTROLLED_WRITE, false));
    }

    @Test
    void editOrRemoveTypeControlledWriteAlwaysAsks() {
        // instance/rename mutates an EXISTING instance — must always ASK; there is no toggle
        // that could relax this.
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true);
        assertEquals(AiExecutionPolicy.Decision.ASK,
                policy.check("instance", "rename", ToolPermission.CONTROLLED_WRITE, false));
        assertEquals(AiExecutionPolicy.Decision.ASK,
                policy.check("account", "set_skin", ToolPermission.CONTROLLED_WRITE, false));
        assertEquals(AiExecutionPolicy.Decision.ASK,
                policy.check("edit", null, ToolPermission.CONTROLLED_WRITE, false));
    }

    @Test
    void editOrRemoveForcedAskAppliesUnderAutoUnlikeTheOldYolo() {
        // Before the SAFE/ASK/YOLO merge, picking YOLO bypassed this forced-ask gate entirely — an
        // unconditional "skip literally everything" escape hatch. Auto has no such independently
        // selectable full-bypass tier anymore (see AiApprovalMode's own doc), so the same call now
        // asks like it always would under the old SAFE/ASK modes.
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true);
        assertEquals(AiExecutionPolicy.Decision.ASK,
                policy.check("instance", "rename", ToolPermission.CONTROLLED_WRITE, false));
    }

    @Test
    void classificationAloneDecidesControlledWriteConfirmation() {
        // The old fileWriteConfirmEnabled toggle (which could force EVERY controlled write to
        // ask) is gone: the create-vs-edit/remove classification is now the ONLY thing deciding
        // whether a CONTROLLED_WRITE confirms — creation allows, edit/remove asks, on the same
        // policy instance.
        AiExecutionPolicy policy = new AiExecutionPolicy(AiApprovalMode.AUTO, true);
        assertEquals(AiExecutionPolicy.Decision.ALLOW,
                policy.check("instance", "create", ToolPermission.CONTROLLED_WRITE, false));
        assertEquals(AiExecutionPolicy.Decision.ASK,
                policy.check("instance", "rename", ToolPermission.CONTROLLED_WRITE, false));
    }

    @Test
    void withModePreservesOtherFlags() {
        AiExecutionPolicy original = new AiExecutionPolicy(AiApprovalMode.AUTO, true, false);
        AiExecutionPolicy remoded = original.withMode(AiApprovalMode.AUTO);
        assertEquals(AiApprovalMode.AUTO, remoded.getMode());
        assertEquals(original.isDangerousConfirmationEnabled(), remoded.isDangerousConfirmationEnabled());
        assertEquals(original.isDangerouslySkipPermissions(), remoded.isDangerouslySkipPermissions());
        // withMode always returns a COPY, never the original instance.
        assertNotSame(original, remoded);
    }
}
