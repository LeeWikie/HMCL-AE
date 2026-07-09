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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for {@link TerminalDenialRegistry} — signature canonicalization ("参数微扰仍命中")
/// and the denial-result predicate (borrow-list A1).
public final class TerminalDenialRegistryTest {

    @Test
    public void reorderedFieldsProduceTheSameSignature() {
        String a = TerminalDenialRegistry.signature("instance",
                "{\"action\":\"delete\",\"instance\":\"OldPack\",\"confirm\":true}");
        String b = TerminalDenialRegistry.signature("instance",
                "{\"confirm\":true,\"instance\":\"OldPack\",\"action\":\"delete\"}");
        assertEquals(a, b, "field order must never matter — that's the exact evasion the raw-JSON "
                + "fingerprint suffered from");
    }

    @Test
    public void nestedObjectKeysAreAlsoOrderInsensitive() {
        String a = TerminalDenialRegistry.signature("t", "{\"opts\":{\"x\":1,\"y\":2},\"id\":\"a\"}");
        String b = TerminalDenialRegistry.signature("t", "{\"id\":\"a\",\"opts\":{\"y\":2,\"x\":1}}");
        assertEquals(a, b);
    }

    @Test
    public void rewordedDescriptionAndBackgroundHintCannotDodgeTheRegistry() {
        String a = TerminalDenialRegistry.signature("instance",
                "{\"action\":\"delete\",\"instance\":\"OldPack\",\"description\":\"删除旧实例\"}");
        String b = TerminalDenialRegistry.signature("instance",
                "{\"action\":\"delete\",\"instance\":\"OldPack\",\"description\":\"清理不需要的实例\",\"background\":false}");
        assertEquals(a, b, "description/background are model-facing decoration, not part of what "
                + "the operation does — rewording them must still hit the denial");
    }

    @Test
    public void differentTargetsGetDifferentSignaturesDigitsIncluded() {
        // Unlike the loop-signature detector, digits must NOT be blanked here: declining
        // "delete A1" says nothing about "delete A2".
        String a1 = TerminalDenialRegistry.signature("instance", "{\"action\":\"delete\",\"instance\":\"A1\"}");
        String a2 = TerminalDenialRegistry.signature("instance", "{\"action\":\"delete\",\"instance\":\"A2\"}");
        assertNotEquals(a1, a2);
        assertNotEquals(
                TerminalDenialRegistry.signature("toolA", "{\"x\":1}"),
                TerminalDenialRegistry.signature("toolB", "{\"x\":1}"),
                "the tool name is part of the key");
    }

    @Test
    public void unparseableArgumentsStillFormAStableKey() {
        String garbage = "not json at all {{{";
        assertEquals(TerminalDenialRegistry.signature("t", garbage),
                TerminalDenialRegistry.signature("t", "  not json at all {{{  ".trim()));
        assertEquals(TerminalDenialRegistry.signature("t", null),
                TerminalDenialRegistry.signature("t", "   "),
                "null and blank arguments are the same (empty) signature");
    }

    @Test
    public void recordThenHitAcrossPerturbations() {
        TerminalDenialRegistry registry = new TerminalDenialRegistry();
        assertFalse(registry.isDenied("instance", "{\"action\":\"delete\",\"instance\":\"OldPack\"}"));

        registry.recordDenial("instance", "{\"action\":\"delete\",\"instance\":\"OldPack\",\"description\":\"x\"}");

        assertTrue(registry.isDenied("instance", "{\"instance\":\"OldPack\",\"action\":\"delete\"}"),
                "reordered + description-stripped repeat must hit");
        assertFalse(registry.isDenied("instance", "{\"action\":\"delete\",\"instance\":\"OtherPack\"}"),
                "a different target is a different operation — not denied");
    }

    @Test
    public void denialResultPredicateMatchesOnlyTheAdapterDeclineTexts() {
        assertTrue(TerminalDenialRegistry.isUserDenialResult(LangChain4jToolAdapter.USER_DECLINED_TEXT),
                "the shared decline constant must be recognized");
        assertTrue(TerminalDenialRegistry.isUserDenialResult(
                "Error: the user declined this CRITICAL operation at the safety prompt. ..."));
        assertFalse(TerminalDenialRegistry.isUserDenialResult(null));
        assertFalse(TerminalDenialRegistry.isUserDenialResult(
                "Found 3 results: the request was declined by the remote server"),
                "ordinary tool output containing the word 'declined' must NOT register a denial");
        assertFalse(TerminalDenialRegistry.isUserDenialResult(
                "Error: connection declined by peer"),
                "only the adapter's exact 'Error: the user declined' prefix counts");
    }

    @Test
    public void shortCircuitTextRidesTheGuardChannelAndReadsAsAFailure() {
        assertTrue(TerminalDenialRegistry.SHORT_CIRCUIT_TEXT.startsWith("BLOCKED: "),
                "the loop's failure detection keys on the Error:/BLOCKED: prefix");
        assertTrue(TerminalDenialRegistry.SHORT_CIRCUIT_TEXT.contains(
                        "<" + GuardMessageFormatter.TAG + " type=\"terminal_denial\">"),
                "the body must ride the runtime-guard identity channel (H2)");
        assertTrue(TerminalDenialRegistry.SHORT_CIRCUIT_TEXT.contains("already declined"));
        assertTrue(org.jackhuang.hmcl.ai.tools.ToolFailures.isWellFormedEnvelope(
                        TerminalDenialRegistry.SHORT_CIRCUIT_TEXT),
                "the short-circuit text must follow the unified failure envelope");
    }
}
