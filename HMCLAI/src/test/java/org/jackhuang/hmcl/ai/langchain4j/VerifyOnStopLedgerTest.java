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

import org.jackhuang.hmcl.ai.langchain4j.LangChain4jChatAdapter.LoopGuardState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Direct unit coverage for the verify-on-stop ledger (borrow-list 2.13 / failure mode E) that
/// replaced the old single {@code sawRiskyWriteSinceVerify} boolean. The old flag was cleared by
/// ANY successful READ_ONLY call anywhere later in the turn — a multi-write turn that only
/// re-checked the LAST write silently counted every earlier write as "verified" too. These tests
/// exercise the ledger's matching heuristic ({@link LangChain4jChatAdapter#riskyWriteSignature}/
/// {@link LangChain4jChatAdapter#clearVerifiedEntries}) directly, independent of the full streaming
/// loop (see {@link TraceLoopEndToEndTest} for the end-to-end regression coverage).
public final class VerifyOnStopLedgerTest {

    /// Core bug fix: two risky writes to DIFFERENT targets via the SAME tool name each need their
    /// own verifying read — a single read that only names the second write's target must NOT clear
    /// the first write's still-outstanding entry.
    @Test
    public void readVerifyingOneTargetDoesNotClearAnotherTargetsOutstandingWrite() {
        LoopGuardState state = new LoopGuardState();
        state.outstandingRiskyWrites.add(
                LangChain4jChatAdapter.riskyWriteSignature("instance_stub", "{\"action\":\"set_memory\",\"instance\":\"A\"}"));
        state.outstandingRiskyWrites.add(
                LangChain4jChatAdapter.riskyWriteSignature("instance_stub", "{\"action\":\"set_memory\",\"instance\":\"B\"}"));
        assertEquals(2, state.outstandingRiskyWrites.size(), "both writes start out unverified");

        // A read-only call that only verifies instance "B".
        LangChain4jChatAdapter.clearVerifiedEntries(state, "instance_stub", "{\"action\":\"details\",\"instance\":\"B\"}");

        assertEquals(1, state.outstandingRiskyWrites.size(),
                "only B's entry should be cleared — A's write is still unverified");
        assertTrue(state.outstandingRiskyWrites.contains(
                        LangChain4jChatAdapter.riskyWriteSignature("instance_stub", "{\"action\":\"set_memory\",\"instance\":\"A\"}")),
                "instance A's outstanding write must survive a read that only targeted instance B");
    }

    /// The common case (unchanged from before the fix): a single risky write immediately followed
    /// by a read verifying that SAME target clears the ledger completely.
    @Test
    public void readVerifyingTheSameTargetClearsTheLedger() {
        LoopGuardState state = new LoopGuardState();
        state.outstandingRiskyWrites.add(
                LangChain4jChatAdapter.riskyWriteSignature("instance_stub", "{\"action\":\"set_memory\",\"instance\":\"A\"}"));

        LangChain4jChatAdapter.clearVerifiedEntries(state, "instance_stub", "{\"action\":\"details\",\"instance\":\"A\"}");

        assertTrue(state.outstandingRiskyWrites.isEmpty(), "a read of the SAME target must verify the write");
    }

    /// A read on a completely different tool name must never clear another tool's outstanding write
    /// — the old boolean cleared on ANY read_only call regardless of tool.
    @Test
    public void readOnADifferentToolDoesNotClearUnrelatedWrite() {
        LoopGuardState state = new LoopGuardState();
        state.outstandingRiskyWrites.add(
                LangChain4jChatAdapter.riskyWriteSignature("account", "{\"action\":\"set_skin\",\"username\":\"Steve\"}"));

        LangChain4jChatAdapter.clearVerifiedEntries(state, "job", "{\"action\":\"list\"}");

        assertEquals(1, state.outstandingRiskyWrites.size(),
                "a read on an unrelated tool must not verify a different tool's write");
    }

    /// A targetless read of the SAME tool (e.g. a bare "list" with no identifying argument) is
    /// treated as surveying everything for that tool, so it verifies EVERY outstanding write made
    /// via that tool, regardless of target.
    @Test
    public void targetlessReadOfSameToolClearsEveryOutstandingTargetForThatTool() {
        LoopGuardState state = new LoopGuardState();
        state.outstandingRiskyWrites.add(
                LangChain4jChatAdapter.riskyWriteSignature("instance_stub", "{\"action\":\"set_memory\",\"instance\":\"A\"}"));
        state.outstandingRiskyWrites.add(
                LangChain4jChatAdapter.riskyWriteSignature("instance_stub", "{\"action\":\"set_memory\",\"instance\":\"B\"}"));

        LangChain4jChatAdapter.clearVerifiedEntries(state, "instance_stub", "{\"action\":\"list\"}");

        assertTrue(state.outstandingRiskyWrites.isEmpty(),
                "a targetless 'list' read surveys everything for that tool and verifies all its writes");
    }

    /// A targetless WRITE (e.g. "create", which doesn't yet have an id to name) is verified by ANY
    /// read of that same tool, targeted or not — there's nothing more specific to require.
    @Test
    public void targetlessWriteIsClearedByAnyReadOfTheSameTool() {
        LoopGuardState state = new LoopGuardState();
        state.outstandingRiskyWrites.add(
                LangChain4jChatAdapter.riskyWriteSignature("instance_stub", "{\"action\":\"create\"}"));

        LangChain4jChatAdapter.clearVerifiedEntries(state, "instance_stub", "{\"action\":\"details\",\"instance\":\"A\"}");

        assertTrue(state.outstandingRiskyWrites.isEmpty(), "a targetless write has nothing more specific to verify against");
    }

    /// Repeated writes to the SAME target collapse into a single ledger entry — one verifying read
    /// is enough regardless of how many times that target was written this turn.
    @Test
    public void repeatedWritesToTheSameTargetCollapseIntoOneEntry() {
        LoopGuardState state = new LoopGuardState();
        state.outstandingRiskyWrites.add(
                LangChain4jChatAdapter.riskyWriteSignature("instance_stub", "{\"action\":\"set_memory\",\"instance\":\"A\",\"maxMemoryMB\":2048}"));
        state.outstandingRiskyWrites.add(
                LangChain4jChatAdapter.riskyWriteSignature("instance_stub", "{\"action\":\"set_option\",\"instance\":\"A\",\"key\":\"k\"}"));

        assertEquals(1, state.outstandingRiskyWrites.size(), "same tool+target writes share one ledger entry");
    }

    /// {@link LangChain4jChatAdapter#extractRiskyWriteTarget} prioritizes the narrower resource
    /// identifier ("mod") over the broader one ("instance") when both are present in one call's
    /// arguments — mirrors e.g. the real `instance` tool's `mods_toggle` action.
    @Test
    public void extractTargetPrefersNarrowerResourceOverBroaderOne() {
        String target = LangChain4jChatAdapter.extractRiskyWriteTarget(
                "{\"action\":\"mods_toggle\",\"instance\":\"A\",\"mod\":\"sodium.jar\"}");
        assertEquals("mod=sodium.jar", target);
    }

    /// Unparseable/absent arguments must fall back to the untargeted "" — matches broadly rather
    /// than becoming permanently unclearable.
    @Test
    public void extractTargetFallsBackToUntargetedForMissingOrUnparseableArguments() {
        assertEquals("", LangChain4jChatAdapter.extractRiskyWriteTarget(null));
        assertEquals("", LangChain4jChatAdapter.extractRiskyWriteTarget(""));
        assertEquals("", LangChain4jChatAdapter.extractRiskyWriteTarget("not json"));
        assertEquals("", LangChain4jChatAdapter.extractRiskyWriteTarget("{\"action\":\"list\"}"));
    }
}
