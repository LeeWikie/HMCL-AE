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
package org.jackhuang.hmcl.ai.llm;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Represents a single message in a chat conversation.
///
/// Each message has a [`role`] — `"system"`, `"user"`, `"assistant"`, or the history-only
/// `"tool"` (a persisted record of one tool invocation, never sent to the model) — and the
/// text [`content`]. Messages additionally carry optional presentation metadata (turn id,
/// timestamp, model, a [`kind`] marker for synthetic events, a tool payload) so the chat
/// view can replay a conversation faithfully instead of degrading everything to plain
/// user/assistant text. All metadata fields are optional and default to absent, keeping
/// old persisted sessions loadable.
///
/// @see LlmClient
@NotNullByDefault
public final class LlmMessage {

    /// Role of a persisted tool-invocation record. History/UI only: these messages are
    /// SKIPPED when the conversation is converted for the model.
    public static final String ROLE_TOOL = "tool";

    /// {@link #getKind()} marker for a synthetic event turn (background-job auto-continue,
    /// crash-report injection…): sent to the model as a normal user turn, but rendered by
    /// the UI as a neutral event pill instead of a user bubble (and never editable).
    public static final String KIND_EVENT = "event";

    @SerializedName("role")
    private String role;

    @SerializedName("content")
    private String content;

    /// Optional token usage for an assistant message. Only persisted for display;
    /// ignored when the conversation is sent to the model.
    @SerializedName("usage")
    @Nullable
    private LlmUsage usage;

    /// Optional presentation kind (see {@link #KIND_EVENT}); `null` for ordinary messages.
    @SerializedName("kind")
    @Nullable
    private String kind;

    /// Groups the messages of one agent turn (user input + tool records + assistant reply).
    @SerializedName("turnId")
    @Nullable
    private String turnId;

    /// Creation time in epoch milliseconds; 0 when unknown (old persisted messages).
    @SerializedName("ts")
    private long timestamp;

    /// The model that produced an assistant message; `null` when unknown.
    @SerializedName("model")
    @Nullable
    private String model;

    /// Reasoning/thinking text that accompanied an assistant message; `null` when absent.
    @SerializedName("reasoning")
    @Nullable
    private String reasoning;

    /// Structured record of a tool invocation for {@link #ROLE_TOOL} messages.
    @SerializedName("tool")
    @Nullable
    private ToolPayload toolPayload;

    /// Package-private no-arg constructor for Gson deserialization.
    LlmMessage() {
    }

    /// Creates a message with the given role and content, stamped with the current time.
    ///
    /// @param role    the role of the message sender; must not be `null`
    /// @param content the text content of the message; must not be `null`
    public LlmMessage(String role, String content) {
        this.role = role;
        this.content = content;
        this.timestamp = System.currentTimeMillis();
    }

    /// Creates a persisted tool-invocation record (role {@link #ROLE_TOOL}). The content is a
    /// short human-readable fallback line so exports and old builds still show something.
    public static LlmMessage toolRecord(ToolPayload payload, @Nullable String turnId) {
        LlmMessage m = new LlmMessage(ROLE_TOOL,
                (payload.success ? "✓ " : "✗ ") + payload.name);
        m.toolPayload = payload;
        m.turnId = turnId;
        return m;
    }

    /// Returns the role of the message sender.
    public String getRole() {
        return role;
    }

    /// Returns the text content of the message.
    public String getContent() {
        return content;
    }

    /// Returns the token usage attached to this message, or `null` if none.
    @Nullable
    public LlmUsage getUsage() {
        return usage;
    }

    /// Attaches token usage to this message (typically an assistant response).
    ///
    /// @param usage the usage to attach, or `null` to clear
    public void setUsage(@Nullable LlmUsage usage) {
        this.usage = usage;
    }

    @Nullable
    public String getKind() {
        return kind;
    }

    public void setKind(@Nullable String kind) {
        this.kind = kind;
    }

    /// Whether this is a synthetic event turn (see {@link #KIND_EVENT}).
    public boolean isEvent() {
        return KIND_EVENT.equals(kind);
    }

    /// Whether this is a persisted tool-invocation record (see {@link #ROLE_TOOL}).
    public boolean isToolRecord() {
        return ROLE_TOOL.equals(role);
    }

    @Nullable
    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(@Nullable String turnId) {
        this.turnId = turnId;
    }

    /// Creation time in epoch milliseconds; 0 when unknown.
    public long getTimestamp() {
        return timestamp;
    }

    @Nullable
    public String getModel() {
        return model;
    }

    public void setModel(@Nullable String model) {
        this.model = model;
    }

    @Nullable
    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(@Nullable String reasoning) {
        this.reasoning = reasoning;
    }

    @Nullable
    public ToolPayload getToolPayload() {
        return toolPayload;
    }

    /// Structured record of one tool invocation, persisted with {@link #ROLE_TOOL} messages so
    /// the chat view can rebuild the tool card when a session is reloaded (previously tool
    /// activity vanished the moment a turn finished, because nothing was persisted).
    public static final class ToolPayload {
        @SerializedName("name")
        public String name;
        /// Raw arguments JSON as the model sent it (may be abridged).
        @SerializedName("args")
        @Nullable
        public String argsJson;
        /// Result text shown in the card body (may be truncated for storage).
        @SerializedName("result")
        @Nullable
        public String resultText;
        @SerializedName("success")
        public boolean success;
    }
}
