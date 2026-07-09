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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/// Per-turn registry of tool operations the USER has explicitly declined (borrow-list A1,
/// "terminal denial short circuit").
///
/// A user's refusal at a confirmation prompt is a TERMINAL state for this turn, not a retryable
/// failure. Before this registry, the only guard was the exact-fingerprint {@code DUP_CALL_LIMIT}
/// counter — the model could re-issue the same dangerous call and re-open the same confirmation
/// dialog two more times (or forever, by permuting argument order) before anything intervened.
/// Now the FIRST decline is recorded here and any later call this turn that resolves to the same
/// (tool, canonical arguments) signature is short-circuited pre-execution — no tool run, no
/// re-prompt.
///
/// # Signature canonicalization ("参数微扰仍命中")
///
/// The key is the tool name plus a canonical serialization of the call's JSON arguments:
///   - object keys are sorted recursively, so field ORDER never matters;
///   - the model-facing decoration keys {@link #IGNORED_KEYS} (`description` — free-prose dialog
///     text the model rewords freely; `background` — a dispatch hint, not part of what the
///     operation does) are dropped at the top level, so rewording them cannot dodge the registry;
///   - argument VALUES are kept verbatim (digits included) — unlike the loop-signature detector,
///     this registry must distinguish "delete instance A1" from "delete instance A2".
/// Unparseable arguments fall back to the raw trimmed string (still an exact-match key).
///
/// Thread-safe: a parallel READ_ONLY batch may contain a force-confirmed MCP call whose decline
/// is recorded from a pool thread, so the backing set is concurrent.
///
/// One instance lives in {@code LangChain4jChatAdapter.LoopGuardState} — scoped to one turn,
/// beside {@code fingerprintCounts} — never shared across turns: the user may change their mind
/// on the next real message.
@NotNullByDefault
public final class TerminalDenialRegistry {

    /// Top-level argument keys excluded from the denial signature — see the class doc.
    private static final Set<String> IGNORED_KEYS = Set.of("description", "background");

    /// Prefix shared by every "the user declined ..." failure text
    /// {@code LangChain4jToolAdapter.execute} produces (normal confirm, force-confirm, and the
    /// CRITICAL red prompt). Deliberately narrow — matching a bare "declined" substring anywhere
    /// would false-positive on ordinary tool output that merely contains the word.
    private static final String USER_DENIAL_PREFIX = "Error: the user declined";

    /// The pre-execution short-circuit text fed back to the model on a repeat of a denied
    /// operation. `BLOCKED:` prefix keeps the loop's failure detection working; the body rides
    /// the {@link GuardMessageFormatter} identity channel established by borrow-list A3.
    public static final String SHORT_CIRCUIT_TEXT = "BLOCKED: " + GuardMessageFormatter.wrap(
            "terminal_denial",
            "This exact operation was already declined by the user this turn, so it was NOT "
                    + "re-attempted and the user was NOT prompted again. Retryable: no — a user "
                    + "refusal is final for this turn; re-issuing the call (in any argument order) "
                    + "will be short-circuited right here again. Next: choose a different action, "
                    + "or ask the user directly what they would prefer instead.");

    private final Set<String> denied = ConcurrentHashMap.newKeySet();

    /// Records that the user declined this exact operation.
    public void recordDenial(String toolName, @Nullable String argumentsJson) {
        denied.add(signature(toolName, argumentsJson));
    }

    /// Whether this exact operation (canonicalized — see class doc) was already declined this turn.
    public boolean isDenied(String toolName, @Nullable String argumentsJson) {
        return !denied.isEmpty() && denied.contains(signature(toolName, argumentsJson));
    }

    /// Whether a tool result text is one of the tool adapter's "user declined" terminal failures.
    public static boolean isUserDenialResult(@Nullable String resultText) {
        return resultText != null && resultText.startsWith(USER_DENIAL_PREFIX);
    }

    /// Canonical (tool, arguments) signature — package-private for direct test coverage,
    /// mirroring {@code LangChain4jChatAdapter}'s other signature helpers.
    static String signature(String toolName, @Nullable String argumentsJson) {
        return toolName + "|" + canonicalizeArguments(argumentsJson);
    }

    private static String canonicalizeArguments(@Nullable String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "";
        }
        try {
            Object parsed = LangChain4jChatAdapter.SIGNATURE_GSON.fromJson(argumentsJson, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                java.util.TreeMap<String, Object> filtered = new java.util.TreeMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String key = String.valueOf(e.getKey());
                    if (!IGNORED_KEYS.contains(key)) {
                        filtered.put(key, LangChain4jChatAdapter.sortKeysForSignature(e.getValue()));
                    }
                }
                return LangChain4jChatAdapter.SIGNATURE_GSON.toJson(filtered);
            }
            if (parsed != null) {
                return LangChain4jChatAdapter.SIGNATURE_GSON.toJson(
                        LangChain4jChatAdapter.sortKeysForSignature(parsed));
            }
        } catch (com.google.gson.JsonSyntaxException e) {
            // Unparseable arguments: exact raw string is still a stable (if fragile) key.
        }
        return argumentsJson.trim();
    }
}
