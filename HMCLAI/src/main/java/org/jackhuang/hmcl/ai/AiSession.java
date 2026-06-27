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
/// and creation/update timestamps. The message list is auto-pruned to keep at most
/// {@value #MAX_MESSAGES} messages (~10 turns of user + assistant). When a new
/// message would exceed the limit, the oldest messages are dropped first.
///
/// Sessions are serializable to JSON via Gson for use with {@link AiSessionStore}.
///
/// All public methods modifying shared state are thread-safe through internal
/// synchronization.
///
/// @see LlmMessage
/// @see AiSessionStore
@NotNullByDefault
public final class AiSession {

    /// The maximum number of messages retained before auto-pruning begins.
    private static final int MAX_MESSAGES = 20;

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
        pruneMessages();
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

    /// Appends a message to the session history.
    ///
    /// If the total number of messages would exceed {@value #MAX_MESSAGES},
    /// the oldest messages are removed from the front of the list before
    /// the new message is appended. The updated-at timestamp is bumped.
    ///
    /// @param message the message to add; must not be `null`
    public synchronized void addMessage(LlmMessage message) {
        messages.add(message);
        pruneMessages();
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

    /// Returns the raw message list for serialization (used by Gson only).
    /// Callers should prefer {@link #getMessages()} for the safe unmodifiable view.
    synchronized List<LlmMessage> messagesForSerialization() {
        return messages;
    }

    /// Prunes messages from the front of the list until the count is within
    /// {@value #MAX_MESSAGES}. Must be called while holding {@code this} lock.
    private void pruneMessages() {
        while (messages.size() > MAX_MESSAGES) {
            messages.remove(0);
        }
    }
}
