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
package org.jackhuang.hmcl.ai.trace;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jackhuang.hmcl.ai.util.AiLog;
import org.jackhuang.hmcl.ai.util.Redactor;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;

/// Append-only, per-session JSONL trace of the full agent interaction — the complete-truth
/// record that the summarised `[AI]` log and the mutable `ai-sessions.json` cannot provide.
///
/// Each call to {@link #record} appends one JSON line to `<traceDir>/<sessionId>.jsonl`
/// after passing the whole line through {@link Redactor} (so secrets never reach a trace that
/// may be uploaded for diagnosis). Deliberately a pure sink: it takes an already-built
/// {@link JsonObject}, so the langchain4j→JSON conversion (and system-prompt hash de-dup) live
/// in the caller and this class stays trivially unit-testable and free of langchain4j deps.
///
/// Fail-safe: any I/O error is swallowed (logged once) — tracing must never break a real turn.
/// The host installs the trace directory and enabled flag via {@link #configure}; until then
/// (e.g. in unit tests without configuration) every call is a no-op.
public final class TraceRecorder {

    private TraceRecorder() {
    }

    private static final Gson GSON = new Gson();
    private static final Object LOCK = new Object();

    private static volatile @Nullable Path traceDir;
    private static volatile boolean enabled;
    private static volatile boolean warnedOnce;
    private static volatile boolean dirEnsured;

    /// Installs the trace output directory and enabled flag. Called by the host (HMCL) at AI init.
    /// A null directory or {@code enabled=false} turns recording into a no-op.
    public static void configure(@Nullable Path dir, boolean on) {
        traceDir = dir;
        enabled = on;
        dirEnsured = false;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled && traceDir != null;
    }

    /// Appends one trace event for {@code sessionId}. The caller supplies the event body
    /// (type-specific fields); this method stamps {@code ts} (epoch millis) if absent, redacts,
    /// and appends. No-op when disabled, unconfigured, or {@code sessionId} is blank.
    public static void record(@Nullable String sessionId, JsonObject event) {
        Path dir = traceDir;
        if (!enabled || dir == null || sessionId == null || sessionId.isBlank() || event == null) {
            return;
        }
        if (!event.has("ts")) {
            event.addProperty("ts", System.currentTimeMillis());
        }
        // Redact every string leaf BEFORE the tree is serialized. A tool's raw arguments/result
        // are stored as an ALREADY-JSON string property (see TraceEvents.tool()), so once Gson
        // serializes the OUTER JsonObject it double-escapes the inner JSON's quotes (`"` becomes
        // `\"` in the final line) — at that point neither Redactor pattern (both expect a LITERAL
        // `"` around the key/value) can match a secret nested one level deep. Redacting each leaf
        // while it is still a plain, unescaped Java string closes that gap.
        redactTree(event);
        String line = GSON.toJson(event);
        String redacted = Redactor.redact(line);
        Path file = dir.resolve(sanitize(sessionId) + ".jsonl");
        synchronized (LOCK) {
            try {
                if (!dirEnsured) {
                    Files.createDirectories(dir);
                    dirEnsured = true;
                }
                Files.writeString(file, redacted + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                if (!warnedOnce) {
                    warnedOnce = true;
                    AiLog.warn("[AI] trace 写入失败（后续同类错误不再提示）：" + e.getMessage());
                }
            }
        }
    }

    /// Resolves the trace file for a session (for the upload path to package it). May not exist.
    public static @Nullable Path traceFile(@Nullable String sessionId) {
        Path dir = traceDir;
        if (dir == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return dir.resolve(sanitize(sessionId) + ".jsonl");
    }

    /// Reads a trace file's full bytes while holding the SAME {@link #LOCK} {@link #record} takes
    /// for its append-write, so the diagnostic-upload path can never observe a write still in
    /// progress (e.g. a chat turn actively appending in the background while the user clicks
    /// "上传诊断信息") as a torn/truncated final JSON line. A plain, unsynchronized
    /// {@code Files.readAllBytes} call — which is what the upload path used before this method
    /// existed — races that write with no ordering guarantee. Returns an empty array (never
    /// {@code null}) when {@code file} is {@code null} or doesn't exist, mirroring {@link #traceFile}'s
    /// "no trace yet" contract so callers can treat a missing trace as an empty one.
    public static byte[] readTraceFile(@Nullable Path file) throws IOException {
        if (file == null) {
            return new byte[0];
        }
        synchronized (LOCK) {
            return Files.exists(file) ? Files.readAllBytes(file) : new byte[0];
        }
    }

    /// Resolves the file an oversized tool result should be offloaded to (see
    /// {@code LangChain4jChatAdapter#truncateToolResult}), so only a head/tail preview needs to
    /// stay in the model's context. Lives next to the trace directory (same host-installed root,
    /// `<traceDir>/../tool-output/<sessionId>/<turnId>/`) rather than inventing a separate location.
    /// Returns {@code null} when unconfigured or {@code sessionId} is blank, matching the trace
    /// no-op contract — the caller falls back to plain substring truncation in that case.
    /// @param turnId    the current turn's id (see {@code TraceContext#turnId()}), folded into the
    ///                   path to avoid a cross-turn collision. {@code cycle} and {@code callIndex}
    ///                   both reset to 0 at the start of EVERY new turn in the same session, but the
    ///                   output directory used to be keyed only by sessionId (stable across the
    ///                   whole multi-turn session) — so a later turn whose first tool call in its
    ///                   first cycle happened to share a tool name with an earlier turn's would
    ///                   silently overwrite that earlier turn's offloaded file, even though the
    ///                   earlier turn's "...saved to <path>..." preview text was still sitting in the
    ///                   growing conversation history (reading it back later would then retrieve the
    ///                   wrong turn's data). Null/blank falls back to a fixed {@code "no-turn"}
    ///                   segment rather than collapsing back to the sessionId-only collision.
    /// @param callIndex this call's position within its cycle's batch of tool calls (0-based).
    ///                   REQUIRED for uniqueness: a cycle can request the SAME tool name more than
    ///                   once (e.g. several `search` calls in one response) — without the index,
    ///                   two such calls whose output both exceeded the truncation cap would resolve
    ///                   to the identical path and clobber each other's offloaded file, silently
    ///                   corrupting whichever one a later `read` on the "saved to <path>" preview
    ///                   text actually retrieves. This also makes concurrent (parallel) execution
    ///                   of same-name calls within a cycle safe, not just sequential ones.
    public static @Nullable Path resolveToolOutputFile(@Nullable String sessionId, @Nullable String turnId,
                                                        String toolName, int cycle, int callIndex) {
        Path dir = traceDir;
        if (!enabled || dir == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String turnSegment = turnId != null && !turnId.isBlank() ? sanitize(turnId) : "no-turn";
        Path outDir = dir.resolveSibling("tool-output").resolve(sanitize(sessionId)).resolve(turnSegment);
        return outDir.resolve(cycle + "-" + callIndex + "-" + sanitize(toolName) + ".txt");
    }

    /// Recursively redacts every JsonPrimitive STRING leaf of {@code element} IN PLACE (objects and
    /// arrays are walked; numbers/booleans/null are left alone). This must run before the tree is
    /// handed to Gson for serialization — see the call site in {@link #record} for why a single
    /// {@link Redactor#redact} pass over the already-serialized line cannot catch a secret nested
    /// inside a JSON-shaped string value (e.g. a tool's raw arguments/result).
    private static void redactTree(JsonElement element) {
        if (element == null) {
            return;
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            for (String key : new ArrayList<>(obj.keySet())) {
                JsonElement child = obj.get(key);
                if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                    obj.addProperty(key, Redactor.redact(child.getAsString()));
                } else {
                    redactTree(child);
                }
            }
        } else if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement child = arr.get(i);
                if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                    arr.set(i, new JsonPrimitive(Redactor.redact(child.getAsString())));
                } else {
                    redactTree(child);
                }
            }
        }
    }

    /// Keeps a session id safe as a file name (ids are UUIDs, but be defensive). The allow-list
    /// keeps `.` unchanged, so a value that reduces to ALL dots (e.g. "." or "..") would otherwise
    /// survive as a bare path-traversal segment when used with `Path.resolve` — neutralize that.
    static String sanitize(String sessionId) {
        String out = sessionId.replaceAll("[^A-Za-z0-9_.-]", "_");
        return out.chars().allMatch(c -> c == '.') ? "_" : out;
    }
}
