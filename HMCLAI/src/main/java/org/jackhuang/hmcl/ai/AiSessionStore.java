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

    /// Single-threaded daemon executor that runs all async saves in FIFO order.
    /// Shared across store instances (in practice there is one store per app).
    private static final java.util.concurrent.ExecutorService SAVE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ai-session-save");
                t.setDaemon(true);
                return t;
            });

    /// Merge flag for {@link #saveAsync()}: `true` while one queued save has not started yet.
    private final java.util.concurrent.atomic.AtomicBoolean savePending =
            new java.util.concurrent.atomic.AtomicBoolean();

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
        if (data == null) {
            // Empty / whitespace / literal-null file (e.g. truncated by a pre-atomic-write crash):
            // nothing to load, but not an error either. Same guard as AiSettings.load.
            return;
        }
        sessions.clear();

        if (data.sessions != null) {
            for (AiSession session : data.sessions) {
                // Skip a malformed entry (e.g. a literal JSON `null` in the "sessions" array)
                // instead of letting session.getId() NPE — mirrors importFromJson()'s identical
                // guard below. Without this, one bad entry aborted the whole loop, and the only
                // caller that catches it (loadOrQuarantine()) reacts by moving the ENTIRE file
                // aside as corrupt, taking every other perfectly valid session down with it.
                if (session == null || session.getId() == null) {
                    continue;
                }
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

    /// Loads sessions, and when the store file EXISTS but cannot be read or parsed, sets the
    /// damaged file aside as `ai-sessions.json.corrupt-<timestamp>` before returning — so the
    /// caller may safely continue (and later {@link #save()}) without a subsequent save
    /// overwriting the only copy of the user's entire conversation history.
    ///
    /// @return `null` when the store loaded fine (or no file existed); otherwise the path the
    ///         corrupt file was moved to (or the original path if even the move failed), so the
    ///         UI can tell the user where their old history is preserved.
    @org.jetbrains.annotations.Nullable
    public synchronized Path loadOrQuarantine() {
        try {
            load();
            return null;
        } catch (Exception e) {
            String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .format(java.time.LocalDateTime.now());
            Path quarantine = filePath.resolveSibling(filePath.getFileName() + ".corrupt-" + stamp);
            try {
                Files.move(filePath, quarantine);
                return quarantine;
            } catch (IOException moveFailure) {
                // Could not set it aside (locked?). Report the original path; save() may still
                // overwrite it, but the caller at least gets to warn the user first.
                return filePath;
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
        // Serialize independent per-session snapshots. Gson reflectively iterates each session's
        // live `messages` list, and the agent thread mutates it concurrently (addMessage / prune)
        // → ConcurrentModificationException that aborts the save (lost save). copyForStore() takes
        // the session's own monitor and copies its messages into a fresh list, so Gson only ever
        // walks the detached copy and never races the live list.
        List<AiSession> snapshot = new ArrayList<>(sessions.size());
        for (AiSession session : sessions.values()) {
            snapshot.add(session.copyForStore());
        }
        data.sessions = snapshot;

        String json = GSON.toJson(data, STORE_TYPE);
        // Atomic write: stage to a temp sibling then move into place, so a crash / full disk / power
        // loss mid-write can never leave a truncated or empty store (which would lose ALL conversations).
        Files.createDirectories(filePath.getParent());
        Path tmp = filePath.resolveSibling(filePath.getFileName().toString() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, filePath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /// 异步保存:提交到单线程队列并合并(已有一次待执行的保存时不再排队——
    /// save() 在执行时才做快照,后到的改动自然被同一次保存捕获或由下一次捕获)。
    ///
    /// 与流式并发的顺序性论证:
    /// - 所有会话数据修改要么在 FX 线程要么在 agent 线程;`save()` 的快照在
    ///   本 store 的 `synchronized` + `copyForStore()`(session 自身监视器)下完成,
    ///   不会乱序、不会撕裂(Gson 只遍历脱离的副本,永不触碰活列表)。
    /// - 单线程执行器保证保存彼此串行(FIFO),后一次保存的快照一定不早于前一次。
    /// - 合并标志只可能"少保存"不可能"存旧盖新":每次执行都取当下快照;
    ///   CAS 失败(合并)意味着队列里已有一个尚未开始的保存任务,该任务开跑时
    ///   先清标志再快照,因此合并前发生的改动必然被它(或其后重新排队的一次)捕获。
    /// - 崩溃瞬间可能丢最后一次保存(同步写时代也可能丢在更早的点);
    ///   正常退出由 UI 侧的 shutdown hook 调 `save()` 兜底(save() synchronized,
    ///   与在途异步保存互斥,最后落盘的一定是最终快照)。
    public void saveAsync() {
        if (!savePending.compareAndSet(false, true)) return;
        SAVE_EXECUTOR.submit(() -> {
            savePending.set(false); // 先清标志:提交点之后的新改动会重新排队
            try {
                save();
            } catch (Exception e) {
                org.jackhuang.hmcl.ai.util.AiLog.warn("[AI] async session save failed: " + e);
            }
        });
    }

    /// 测试钩子:向保存队列提交一个哨兵任务并阻塞等待其完成。单线程 FIFO 保证
    /// 在此之前排队的所有异步保存任务都已执行完毕。
    static void awaitSaveQueue() throws InterruptedException, java.util.concurrent.ExecutionException {
        SAVE_EXECUTOR.submit(() -> { }).get();
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

    /// Forks a new session containing {@code source}'s messages from index 0 up to and
    /// including {@code includeUpToIndex}, marks it current, and returns it. The original
    /// session is left untouched. The caller should {@link #save()} afterwards.
    public synchronized AiSession createBranch(AiSession source, int includeUpToIndex, String title) {
        List<org.jackhuang.hmcl.ai.llm.LlmMessage> src = source.getMessages();
        List<org.jackhuang.hmcl.ai.llm.LlmMessage> copied = new ArrayList<>();
        for (int i = 0; i <= includeUpToIndex && i < src.size(); i++) {
            copied.add(src.get(i));
        }
        AiSession branch = new AiSession(java.util.UUID.randomUUID().toString(), title,
                Instant.now(), Instant.now(), copied);
        sessions.put(branch.getId(), branch);
        currentSessionId = branch.getId();
        return branch;
    }

    /// Merges sessions from an exported store JSON (the `.json` produced by "导出全部会话") into this
    /// store. Sessions whose id already exists are left untouched — import never overwrites current
    /// data, so re-importing your own export is idempotent — only genuinely new sessions are added.
    /// The caller should {@link #save()} afterwards.
    ///
    /// @param json the raw JSON of an exported store file
    /// @return the number of sessions actually added (new ids only)
    /// @throws JsonParseException if the text is not valid store JSON
    public synchronized int importFromJson(String json) {
        StoreData data = GSON.fromJson(json, STORE_TYPE);
        if (data == null || data.sessions == null) {
            return 0;
        }
        int added = 0;
        for (AiSession session : data.sessions) {
            if (session == null || session.getId() == null || sessions.containsKey(session.getId())) {
                continue;
            }
            sessions.put(session.getId(), session);
            added++;
        }
        return added;
    }

    /// Lists all sessions ordered by most-recently-updated first.
    ///
    /// @return an unmodifiable list of sessions
    public synchronized List<AiSession> listSessions() {
        return sessions.values().stream()
                .sorted(Comparator.comparing(AiSession::isPinned).reversed()
                        .thenComparing(Comparator.comparing(AiSession::getUpdatedAt).reversed()))
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
