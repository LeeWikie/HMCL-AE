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
package org.jackhuang.hmcl.ai.diagnostic;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.ai.util.Redactor;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;

/// Builds the simplified, human-readable "what the chat UI actually shows" diagnostic trace —
/// user messages, assistant text replies, and tool calls (name + arguments + result) — as a
/// SECOND, much smaller file uploaded alongside the existing raw `ai-trace.jsonl` wire-level
/// event log. It never replaces or alters that file; see {@link DiagnosticUploader}.
///
/// Deliberately built from the app's OWN chat session model ({@link AiSession} /
/// {@link LlmMessage}) — the exact same data `AIMainPage#loadSessionMessages` reads to render the
/// chat UI — instead of a filtered re-parse of the wire-level trace. That model is already free of
/// the system prompt and the tool/schema list, and critically it never carries the per-turn
/// `<turn-context>` block the agent appends to the OUTBOUND copy of the latest user message before
/// it reaches the model: `ChatAgent#attachVolatileSuffix` builds that block into a brand-new
/// wire-only {@link LlmMessage} instance for the request only and never writes it back into the
/// session, so {@link AiSession#getMessages()} — and therefore this builder — never sees it. No
/// separate "strip the enrichment" step is needed here; the source data is already exactly the
/// UI-visible shape.
///
/// Output is one JSON object per line (JSONL), matching `ai-trace.jsonl`'s existing convention.
/// Every string value that could carry a secret (tool arguments, tool results, message text) is
/// passed through {@link Redactor#redact} before being written — the same redaction the raw trace
/// gets — so this file is exactly as safe to upload as the one it accompanies.
public final class UiTraceBuilder {

    private UiTraceBuilder() {
    }

    /// File name used for this trace inside the diagnostic-upload zip.
    public static final String FILE_NAME = "ui-trace.jsonl";

    private static final Gson GSON = new Gson();

    /// Builds the simplified trace for {@code session}'s current message list.
    public static byte[] build(AiSession session) {
        return build(session.getMessages());
    }

    /// Builds the simplified trace for an explicit message list — the test seam, and the path
    /// used when a caller already holds a snapshot (e.g. {@link AiSession#getMessages()}) rather
    /// than the live session object.
    public static byte[] build(List<LlmMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (LlmMessage m : messages) {
            JsonObject entry = toEntry(m);
            if (entry != null) {
                sb.append(GSON.toJson(entry)).append('\n');
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /// Converts one session message into its UI-visible trace entry, or `null` when the message
    /// carries nothing the chat UI itself renders. There is currently no producer for such a
    /// message in a freshly created session (every {@code addMessage} call site writes a user,
    /// assistant, event, or tool-record message) — the `null` case only guards old persisted
    /// sessions that might carry a legacy shape, so a stray message can never crash the upload path.
    @Nullable
    private static JsonObject toEntry(LlmMessage m) {
        if (m.isToolRecord()) {
            return toolEntry(m);
        }
        if (m.isEvent()) {
            // Synthetic turn (background-job auto-continue / crash injection): rendered by the UI
            // as a neutral event pill rather than a user bubble, but still something the user sees.
            return textEntry("event", m);
        }
        String role = m.getRole();
        if ("user".equals(role)) {
            return textEntry("user", m);
        }
        if ("assistant".equals(role)) {
            JsonObject o = textEntry("assistant", m);
            if (m.getModel() != null) {
                o.addProperty("model", m.getModel());
            }
            return o;
        }
        return null;
    }

    private static JsonObject textEntry(String type, LlmMessage m) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("ts", m.getTimestamp());
        if (m.getTurnId() != null) {
            o.addProperty("turnId", m.getTurnId());
        }
        o.addProperty("text", Redactor.redact(m.getContent()));
        return o;
    }

    private static JsonObject toolEntry(LlmMessage m) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "tool");
        o.addProperty("ts", m.getTimestamp());
        if (m.getTurnId() != null) {
            o.addProperty("turnId", m.getTurnId());
        }
        LlmMessage.ToolPayload p = m.getToolPayload();
        if (p == null) {
            // Defensive: a ROLE_TOOL message should always carry a payload, but never let a
            // malformed/legacy persisted entry crash the upload path.
            o.addProperty("name", Redactor.redact(m.getContent()));
            return o;
        }
        o.addProperty("name", Redactor.redact(p.name));
        if (p.argsJson != null) {
            o.addProperty("args", Redactor.redact(p.argsJson));
        }
        if (p.resultText != null) {
            o.addProperty("result", Redactor.redact(p.resultText));
        }
        o.addProperty("success", p.success);
        return o;
    }
}
