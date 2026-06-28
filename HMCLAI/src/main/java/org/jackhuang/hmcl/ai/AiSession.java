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
package org.jackhuang.hmcl.ai;

import com.google.gson.annotations.SerializedName;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/// Conversation session that stores message history alongside stable metadata.
///
/// Each session carries a unique {@link #id}, a user-editable {@link #title},
/// and creation/update timestamps. The message list is auto-pruned to keep the
/// estimated token count within the configured budget (default 100K tokens,
/// Older messages are dropped first when the budget is exceeded.
///
/// Token estimation uses a simple character-count heuristic (~4 chars per token),
/// which is conservative but sufficient for context window management.
///
/// Sessions are serializable to JSON via Gson for use with {@link AiSessionStore}.
@NotNullByDefault
public final class AiSession {

    /// Maximum estimated tokens to retain in context (100K default).
    private volatile int maxContextTokens = 100_000;

    /// Rough chars-per-token heuristic used for pruning decisions.
    private static final int CHARS_PER_TOKEN = 4;
    /// Safety margin: reserve ~20% of context for the response.
    private static final double BUDGET_RATIO = 0.8;

    @SerializedName("id")
    private final String id;

    @SerializedName("title")
    private volatile String title;

    @SerializedName("createdAt")
    private Instant createdAt;

    @SerializedName("updatedAt")
    private volatile Instant updatedAt;

    @SerializedName("messages")
    private List<LlmMessage> messages = new ArrayList<>();

    /// Creates an empty session with a generated UUID, default title, and current
    /// timestamps.
    public AiSession() {
        this(UUID.randomUUID().toString(), "New Chat", Instant.now(), Instant.now(),
                new ArrayList<>());
    }

    /// Creates a session with explicit metadata, used during deserialization.
    ///
    /// @param id        the unique session identifier; must not be `null`
    /// @param title     the human-readable title; must not be `null`
    /// @param createdAt the creation timestamp; must not be `null`
    /// @param updatedAt the last-update timestamp; must not be `null`
    /// @param messages  the initial message list; defensive copy is made
    public AiSession(String id, String title, Instant createdAt, Instant updatedAt,
                     List<LlmMessage> messages) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.messages.addAll(messages);
        pruneByTokenBudget();
    }

    /// Returns the unique session identifier.
    public String getId() {
        return id;
    }

    /// Returns the human-readable session title.
    public String getTitle() {
        return title;
    }

    /// Updates the session title and bumps the updated-at timestamp.
    ///
    /// @param title the new title; must not be `null`
    public synchronized void setTitle(String title) {
        this.title = title;
        this.updatedAt = Instant.now();
    }

    /// Returns the creation timestamp.
    public Instant getCreatedAt() {
        return createdAt;
    }

    /// Returns the last-update timestamp.
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /// Sets the maximum context token budget for this session, typically
    /// derived from the model's context window size (e.g. 128000 for GPT-4o).
    public void setContextBudget(int modelContextWindow) {
        this.maxContextTokens = (int) (modelContextWindow * BUDGET_RATIO);
    }

    /// Appends a message to the session history. If the estimated token count
    /// exceeds the context budget, oldest messages are pruned.
    public synchronized void addMessage(LlmMessage message) {
        messages.add(message);
        pruneByTokenBudget();
        this.updatedAt = Instant.now();
    }

    /// Removes the message at {@code index} and every message after it. Used by the UI when
    /// the user edits or regenerates from a point in the conversation. No-op if out of range.
    public synchronized void truncateFrom(int index) {
        if (index < 0 || index >= messages.size()) {
            return;
        }
        messages.subList(index, messages.size()).clear();
        this.updatedAt = Instant.now();
    }

    /// Returns the number of messages currently in the session.
    public synchronized int size() {
        return messages.size();
    }

    /// Clears all messages from the session and bumps the updated-at timestamp.
    ///
    /// Call this when the user invokes the "new conversation" action.
    public synchronized void clear() {
        messages.clear();
        this.updatedAt = Instant.now();
    }

    /// Returns the current message history as an unmodifiable view.
    ///
    /// The returned list is a snapshot — modifications to the session after
    /// this call are not reflected.
    ///
    /// @return an unmodifiable list of messages in insertion order (oldest first)
    public synchronized List<LlmMessage> getMessages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    /// Returns the raw message list for serialization (used by Gson only).
    /// Callers should prefer {@link #getMessages()} for the safe unmodifiable view.
    synchronized List<LlmMessage> messagesForSerialization() {
        return messages;
    }

    /// Prunes oldest messages until the estimated token count is within the budget.
    private void pruneByTokenBudget() {
        while (estimateTokens() > maxContextTokens && messages.size() > 2) {
            messages.remove(0); // drop oldest
        }
    }

    /// Estimates total tokens by summing per-message char counts divided by 4.
    private int estimateTokens() {
        int chars = 0;
        for (LlmMessage m : messages) {
            String c = m.getContent();
            if (c != null) chars += c.length();
        }
        return chars / CHARS_PER_TOKEN;
    }
}
