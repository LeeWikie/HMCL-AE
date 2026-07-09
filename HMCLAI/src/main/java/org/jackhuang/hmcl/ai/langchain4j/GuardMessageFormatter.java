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

import dev.langchain4j.data.message.UserMessage;
import org.jetbrains.annotations.NotNullByDefault;

/// The single identity channel for every runtime-guard message the harness injects into the
/// model's conversation (loop warnings, no-progress nudges, todo-discipline nudges,
/// verify-on-stop, forced finish, terminal-denial short circuits, recalled memories).
///
/// # Why role=user + a wrapper tag, not a mid-history system message
///
/// Evaluated for borrow-list A3 ("system-reminder identity channel"): langchain4j's message list
/// DOES accept a [`dev.langchain4j.data.message.SystemMessage`] at any position, but that is not
/// usable here in practice:
///
///   - langchain4j's Anthropic mapper hoists EVERY `SystemMessage` into the request's single
///     top-level `system` field, silently moving a mid-conversation guard message to the front of
///     the prompt — the positional meaning ("this happened right after cycle N's tool result") is
///     destroyed exactly where it matters most.
///   - Users configure arbitrary OpenAI-compatible backends; several of them (and several local
///     gateways) reject or mishandle a non-leading `system` role message outright.
///
/// So the guard channel keeps `role=user` on the wire — universally accepted — and instead gives
/// every guard message a machine-readable wrapper: `<runtime-guard type="...">…</runtime-guard>`.
/// The system prompt (see {@code AiPromptBuilder}) teaches the tag's semantics ONCE: content in
/// this tag is the runtime harness speaking, with the same authority as a tool error — never the
/// user, never a new user request.
///
/// Type tokens in use: `todo_silent_discard`, `todo_stale`, `loop_warning`, `no_progress`,
/// `verify_on_stop`, `force_finish`, `terminal_denial`, `recalled_memories`. Keep tokens
/// lower_snake_case so they read as machine identifiers, not prose.
@NotNullByDefault
public final class GuardMessageFormatter {

    /// The wrapper tag name, referenced by {@code AiPromptBuilder}'s one-time system-prompt
    /// education so the taught name can never drift from the injected one.
    public static final String TAG = "runtime-guard";

    private GuardMessageFormatter() {
    }

    /// Wraps guard text in the identity tag: `<runtime-guard type="{type}">\n{text}\n</runtime-guard>`.
    public static String wrap(String type, String text) {
        return "<" + TAG + " type=\"" + type + "\">\n" + text + "\n</" + TAG + ">";
    }

    /// Builds the guard message actually injected into the conversation: a `role=user` message
    /// (see the class doc for why not system) whose whole content is the wrapped guard text.
    public static UserMessage guardMessage(String type, String text) {
        return UserMessage.from(wrap(type, text));
    }
}
