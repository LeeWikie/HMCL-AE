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
/// and creation/update timestamps.
///
/// The session stores the FULL history: fitting the conversation into the model's
/// context window is a REQUEST-scope concern handled when the prompt is built
/// (see {@code ChatAgent.buildMessages}). An earlier design pruned this list itself
/// on every add and the truncated list was then persisted — permanently deleting the
/// user's earliest messages from disk whenever a small context window was configured.
///
/// Sessions are serializable to JSON via Gson for use with {@link AiSessionStore}.
@NotNullByDefault
public final class AiSession {

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
    }

    /// Private copy constructor used by {@link #copyForStore()} to snapshot a session for
    /// serialization. Copies every field verbatim — including the full message list — so the
    /// saved JSON matches the live state exactly. Callers must hold {@code other}'s monitor
    /// (see {@link #copyForStore()}) so the message copy is consistent with concurrent mutators.
    private AiSession(AiSession other) {
        this.id = other.id;
        this.title = other.title;
        this.createdAt = other.createdAt;
        this.updatedAt = other.updatedAt;
        this.messages = new ArrayList<>(other.messages);
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

    /// Appends a message to the session history. The full history is kept — fitting the
    /// conversation into the model's context window happens at request-build time, never here.
    public synchronized void addMessage(LlmMessage message) {
        messages.add(message);
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

    /// Removes the single message at {@code index} so it disappears from the conversation
    /// context. No-op if the index is out of range.
    public synchronized void removeAt(int index) {
        if (index < 0 || index >= messages.size()) {
            return;
        }
        messages.remove(index);
        this.updatedAt = Instant.now();
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

    /// Returns an independent, point-in-time copy of this session whose message list is a fresh
    /// snapshot, safe to hand to Gson on another thread (e.g. the FX thread during
    /// {@link AiSessionStore#save()}) while this session keeps being mutated by the agent thread.
    /// Taken under this session's monitor, so it is consistent with {@link #addMessage} and the
    /// other synchronized mutators.
    synchronized AiSession copyForStore() {
        return new AiSession(this);
    }
}
