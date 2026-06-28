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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/// Manages persistent storage and lifecycle of {@link AiSession} instances.
///
/// Sessions are persisted as a JSON array in `{configDir}/ai-sessions.json`.
/// The store also tracks a `currentSessionId` so the UI can restore the
/// last-active session across restarts.
///
/// ## Thread safety
///
/// The store uses a simple {@code synchronized} policy on public mutating methods.
/// Reads internal to the class's persistence methods hold the lock briefly for
/// snapshot creation.
///
/// @see AiSession
@NotNullByDefault
public final class AiSessionStore {

    /// The file name used for persisting session data.
    public static final String FILE_NAME = "ai-sessions.json";

    /// JSON wrapper DTO for the store file, holding the session list and
    /// the current session id.
    @SuppressWarnings({"FieldMayBeFinal", "CanBeFinal"})
    private static final class StoreData {
        @SerializedName("currentSessionId")
        @Nullable
        String currentSessionId = null;

        @SerializedName("sessions")
        List<AiSession> sessions = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .registerTypeAdapter(Instant.class, new InstantAdapter())
            .create();

    private static final Type STORE_TYPE = new TypeToken<StoreData>() {}.getType();

    private final Path filePath;

    /// The in-memory session map keyed by session id. Preserves insertion order.
    private final Map<String, AiSession> sessions = new LinkedHashMap<>();

    /// The currently active session id, or `null` if no session is selected.
    @Nullable
    private volatile String currentSessionId;

    /// Creates a store bound to the given config directory.
    ///
    /// @param configDir the `.hmcl/` directory path; the store file will be
    ///                  written as `{configDir}/ai-sessions.json`
    public AiSessionStore(Path configDir) {
        this.filePath = configDir.resolve(FILE_NAME);
    }

    /// Loads sessions from the JSON file. If the file does not exist or is
    /// unreadable, the store remains empty.
    ///
    /// @throws IOException if an I/O error occurs while reading
    /// @throws JsonParseException if the file content is not valid JSON
    public synchronized void load() throws IOException {
        String json;
        try {
            json = Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return;
        }

        StoreData data = GSON.fromJson(json, STORE_TYPE);
        sessions.clear();

        if (data.sessions != null) {
            for (AiSession session : data.sessions) {
                sessions.put(session.getId(), session);
            }
        }
        this.currentSessionId = data.currentSessionId;

        // Validate that the current session id references a known session.
        if (currentSessionId != null && !sessions.containsKey(currentSessionId)) {
            if (!sessions.isEmpty()) {
                currentSessionId = sessions.keySet().iterator().next();
            } else {
                currentSessionId = null;
            }
        }
    }

    /// Saves the current sessions and current session id to the JSON file.
    ///
    /// The parent directories of the store file are created if they do not exist.
    ///
    /// @throws IOException if an I/O error occurs while writing
    public synchronized void save() throws IOException {
        Files.createDirectories(filePath.getParent());

        StoreData data = new StoreData();
        data.currentSessionId = currentSessionId;
        data.sessions = new ArrayList<>(sessions.values());

        String json = GSON.toJson(data, STORE_TYPE);
        Files.writeString(filePath, json, StandardCharsets.UTF_8);
    }

    /// Creates a new session, adds it to the store, sets it as current, and
    /// returns the created instance. The caller should call {@link #save()}
    /// after mutating operations to persist changes.
    ///
    /// @return the newly created session
    public synchronized AiSession createSession() {
        AiSession session = new AiSession();
        sessions.put(session.getId(), session);
        currentSessionId = session.getId();
        return session;
    }

    /// Lists all sessions ordered by most-recently-updated first.
    ///
    /// @return an unmodifiable list of sessions
    public synchronized List<AiSession> listSessions() {
        return sessions.values().stream()
                .sorted(Comparator.comparing(AiSession::getUpdatedAt).reversed())
                .collect(Collectors.toUnmodifiableList());
    }

    /// Returns the session with the given id, or `null` if not found.
    ///
    /// @param sessionId the session identifier
    /// @return the session, or `null`
    @Nullable
    public synchronized AiSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /// Deletes the session with the given id from the store.
    ///
    /// If the deleted session was the current session, the current session id
    /// is cleared. The caller should call {@link #save()} to persist changes.
    ///
    /// @param sessionId the session identifier to remove
    /// @return `true` if a session was removed, `false` if not found
    public synchronized boolean deleteSession(String sessionId) {
        boolean wasCurrent = sessionId.equals(currentSessionId);

        // Resolve the sequential neighbour in the displayed (most-recently-updated)
        // order BEFORE removal, so deleting the current session advances to the next
        // item in the visible list — or the previous one when deleting the last item —
        // instead of an arbitrary insertion-order entry.
        String nextId = null;
        if (wasCurrent) {
            List<AiSession> ordered = listSessions();
            int idx = -1;
            for (int i = 0; i < ordered.size(); i++) {
                if (ordered.get(i).getId().equals(sessionId)) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                if (idx + 1 < ordered.size()) {
                    nextId = ordered.get(idx + 1).getId();
                } else if (idx - 1 >= 0) {
                    nextId = ordered.get(idx - 1).getId();
                }
            }
        }

        boolean removed = sessions.remove(sessionId) != null;
        if (removed && wasCurrent) {
            currentSessionId = nextId;
        }
        return removed;
    }

    /// Returns the current session id, or `null` if none is set.
    @Nullable
    public String getCurrentSessionId() {
        return currentSessionId;
    }

    /// Sets the current session id. The caller should call {@link #save()}
    /// to persist the change.
    ///
    /// @param sessionId the session id to mark as current; may be `null`
    public synchronized void setCurrentSessionId(@Nullable String sessionId) {
        if (sessionId != null && !sessions.containsKey(sessionId)) {
            return; // silently ignore unknown ids
        }
        this.currentSessionId = sessionId;
    }

    /// Returns the current session, or `null` if none is set.
    @Nullable
    public synchronized AiSession getCurrentSession() {
        if (currentSessionId == null) {
            return null;
        }
        return sessions.get(currentSessionId);
    }

    /// Returns the number of sessions currently in the store.
    public synchronized int size() {
        return sessions.size();
    }

    // ---- Gson type adapter for java.time.Instant ----------------------------------

    /// Serializes {@link Instant} as epoch milliseconds so Gson does not attempt
    /// deep reflection on {@code java.time} internals.
    private static final class InstantAdapter implements JsonSerializer<Instant>, JsonDeserializer<Instant> {
        @Override
        public JsonElement serialize(Instant src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.toEpochMilli());
        }

        @Override
        public Instant deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {
            return Instant.ofEpochMilli(json.getAsLong());
        }
    }
}
