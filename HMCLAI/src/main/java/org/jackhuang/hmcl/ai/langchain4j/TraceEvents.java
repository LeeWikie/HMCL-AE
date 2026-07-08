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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.jackhuang.hmcl.ai.trace.TraceContext;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// Builds the {@link JsonObject} trace events for the agent tool loop from langchain4j message
/// types. Kept in the langchain4j package (it needs those types); {@code TraceRecorder} stays a
/// langchain4j-free pure sink. The FULL messages / raw response / complete tool IO are captured
/// here — the whole point is a truth record, so nothing is truncated.
///
/// NOTE (体积): messages/tools are recorded IN FULL every cycle (system prompt repeats). Alpha
/// doesn't care about size; system-prompt/tools SHA-256 de-dup is a later optimization (TODO).
final class TraceEvents {

    private TraceEvents() {
    }

    /// A model request: the complete conversation sent this cycle + the tool schemas offered.
    static JsonObject request(TraceContext ctx, int cycle, int attempt,
                              List<ChatMessage> conversation,
                              @Nullable List<ToolSpecification> tools, boolean toolsOn) {
        JsonObject e = base("request", ctx, cycle);
        e.addProperty("attempt", attempt);
        e.addProperty("toolsOn", toolsOn);
        e.add("messages", messagesJson(conversation));
        if (tools != null && !tools.isEmpty()) {
            e.add("tools", toolsJson(tools));
        }
        return e;
    }

    /// The model's raw response for a cycle: text, tool calls (raw arguments), finish reason, usage.
    static JsonObject response(TraceContext ctx, int cycle, @Nullable AiMessage aiMessage,
                               @Nullable String finishReason,
                               @Nullable Integer inTok, @Nullable Integer outTok, @Nullable Integer totalTok,
                               long elapsedMs) {
        JsonObject e = base("response", ctx, cycle);
        e.addProperty("elapsedMs", elapsedMs);
        if (finishReason != null) e.addProperty("finishReason", finishReason);
        if (inTok != null) e.addProperty("inTokens", inTok);
        if (outTok != null) e.addProperty("outTokens", outTok);
        if (totalTok != null) e.addProperty("totalTokens", totalTok);
        if (aiMessage != null) {
            if (aiMessage.text() != null) e.addProperty("content", aiMessage.text());
            if (aiMessage.hasToolExecutionRequests()) {
                e.add("toolCalls", toolCallsJson(aiMessage.toolExecutionRequests()));
            }
        }
        return e;
    }

    /// One tool invocation: name, raw arguments, the COMPLETE result (before any truncation the
    /// model sees), success, and whether the model got a truncated view.
    static JsonObject tool(TraceContext ctx, int cycle, String name, @Nullable String arguments,
                           @Nullable String fullResult, boolean success, boolean truncatedForModel) {
        JsonObject e = base("tool", ctx, cycle);
        e.addProperty("name", name);
        if (arguments != null) e.addProperty("arguments", arguments);
        if (fullResult != null) e.addProperty("result", fullResult);
        e.addProperty("success", success);
        e.addProperty("truncatedForModel", truncatedForModel);
        return e;
    }

    /// A loop-guard decision: which guard fired and why (DUP_BLOCKED / NO_PROGRESS / CYCLE_CAP / RETRY).
    static JsonObject guard(TraceContext ctx, int cycle, String kind, @Nullable String detail) {
        JsonObject e = base("guard", ctx, cycle);
        e.addProperty("kind", kind);
        if (detail != null) e.addProperty("detail", detail);
        return e;
    }

    // ---- helpers ----

    private static JsonObject base(String type, TraceContext ctx, int cycle) {
        JsonObject e = new JsonObject();
        e.addProperty("type", type);
        if (ctx.turnId() != null) e.addProperty("turnId", ctx.turnId());
        e.addProperty("cycle", cycle);
        return e;
    }

    private static JsonArray messagesJson(List<ChatMessage> conversation) {
        JsonArray arr = new JsonArray();
        for (ChatMessage m : conversation) {
            arr.add(messageJson(m));
        }
        return arr;
    }

    private static JsonObject messageJson(ChatMessage m) {
        JsonObject o = new JsonObject();
        if (m instanceof SystemMessage s) {
            o.addProperty("role", "system");
            o.addProperty("content", s.text());
        } else if (m instanceof UserMessage u) {
            o.addProperty("role", "user");
            o.addProperty("content", userText(u));
        } else if (m instanceof AiMessage a) {
            o.addProperty("role", "assistant");
            if (a.text() != null) o.addProperty("content", a.text());
            if (a.hasToolExecutionRequests()) {
                o.add("toolCalls", toolCallsJson(a.toolExecutionRequests()));
            }
        } else if (m instanceof ToolExecutionResultMessage t) {
            o.addProperty("role", "tool");
            if (t.toolName() != null) o.addProperty("toolName", t.toolName());
            if (t.id() != null) o.addProperty("id", t.id());
            o.addProperty("content", t.text());
        } else {
            o.addProperty("role", "other");
            o.addProperty("content", String.valueOf(m));
        }
        return o;
    }

    private static String userText(UserMessage u) {
        try {
            return u.singleText();
        } catch (RuntimeException e) {
            // multi-part (e.g. image) content — fall back to the full toString rather than losing it
            return String.valueOf(u);
        }
    }

    private static JsonArray toolCallsJson(List<ToolExecutionRequest> requests) {
        JsonArray arr = new JsonArray();
        for (ToolExecutionRequest r : requests) {
            JsonObject c = new JsonObject();
            c.addProperty("name", r.name());
            if (r.id() != null) c.addProperty("id", r.id());
            if (r.arguments() != null) c.addProperty("arguments", r.arguments());
            arr.add(c);
        }
        return arr;
    }

    private static JsonArray toolsJson(List<ToolSpecification> tools) {
        JsonArray arr = new JsonArray();
        for (ToolSpecification ts : tools) {
            JsonObject o = new JsonObject();
            o.addProperty("name", ts.name());
            if (ts.description() != null) o.addProperty("description", ts.description());
            if (ts.parameters() != null) o.addProperty("parameters", String.valueOf(ts.parameters()));
            arr.add(o);
        }
        return arr;
    }
}
