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
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.ai.net.ProxyAuthenticatorHolder;
import org.jackhuang.hmcl.ai.trace.TraceRecorder;
import org.jackhuang.hmcl.ai.util.Redactor;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/// Packages a session's agent trace (already secret-redacted at write time) plus a small metadata
/// file into a zip and POSTs it to the diagnostic endpoint for post-mortem debugging.
///
/// Alongside the raw `ai-trace.jsonl` wire-level event log, the zip also carries
/// {@link UiTraceBuilder}'s `ui-trace.jsonl` — a much smaller, simplified trace containing only
/// what the chat UI itself displays (user messages, assistant replies, tool calls), for a reader
/// who just wants to see what happened without wading through repeated system-prompt/tool-list
/// payloads. Both files go through the same {@link Redactor}-backed redaction.
///
/// The trace is the truth record we otherwise can't get from the user; this is how it reaches us.
/// The client is a thin, testable seam: {@link #buildZip} is pure (unit-tested), {@link #post}
/// hits an HTTP endpoint (tested against a local fake server), and {@link #upload} wires them.
public final class DiagnosticUploader {

    private DiagnosticUploader() {
    }

    private static final Gson GSON = new Gson();

    /// The server rejects bodies over 10 MiB; check locally so we fail with a clear message.
    static final long MAX_ZIP_BYTES = 10L * 1024 * 1024;

    /// Outcome of an upload: {@code ok} with a short reference {@code id} the user can quote, or a
    /// failure with a human-readable {@code error}.
    public record UploadResult(boolean ok, @Nullable String id, @Nullable String error) {
    }

    /// Reads the session trace, builds the zip (trace + metadata), and uploads it. Metadata never
    /// includes the API key; the free-text {@code description} is redacted defensively. All failures
    /// are returned as a {@link UploadResult}, never thrown.
    ///
    /// This overload packs no {@code ui-trace.jsonl} (empty message list) — kept only so callers
    /// that genuinely have no session message list (e.g. a hypothetical future non-chat trace)
    /// still compile. The real chat-upload path is {@link #upload(Path, String, String, String,
    /// String, List)}.
    public static UploadResult upload(Path traceFile, String version, String os,
                                      @Nullable String model, @Nullable String description) {
        return upload(traceFile, version, os, model, description, null);
    }

    /// Same as {@link #upload(Path, String, String, String, String)}, additionally packing
    /// {@link UiTraceBuilder}'s simplified `ui-trace.jsonl` built from {@code uiMessages} — the
    /// SAME session's message list ({@link org.jackhuang.hmcl.ai.AiSession#getMessages()}) the
    /// caller used to resolve {@code traceFile}'s session, so both files in the zip describe
    /// exactly one session. `null`/empty produces an empty `ui-trace.jsonl`, never an error.
    public static UploadResult upload(Path traceFile, String version, String os,
                                      @Nullable String model, @Nullable String description,
                                      @Nullable List<LlmMessage> uiMessages) {
        return upload(traceFile, version, os, model, description, uiMessages, AgentEndpoints.FEEDBACK_URL);
    }

    /// Same as {@link #upload(Path, String, String, String, String, List)} but with the target URL
    /// as a parameter — a test seam so a concurrent-write-during-read regression test can point this
    /// at a local fake server instead of the real {@link AgentEndpoints#FEEDBACK_URL}.
    static UploadResult upload(Path traceFile, String version, String os,
                               @Nullable String model, @Nullable String description,
                               @Nullable List<LlmMessage> uiMessages, String url) {
        try {
            // Goes through TraceRecorder's own write lock (see #readTraceFile) instead of a bare
            // Files.readAllBytes: without it, this read can race TraceRecorder#record's append-write
            // for the same session (e.g. a chat turn still streaming in the background while the user
            // clicks "上传诊断信息") and pack a torn/truncated final JSON line into the uploaded zip.
            byte[] trace = TraceRecorder.readTraceFile(traceFile);
            // UiTraceBuilder redacts every string leaf itself (see its class doc) — no extra pass
            // needed here, mirroring how `trace` above already arrives pre-redacted from disk.
            byte[] uiTrace = UiTraceBuilder.build(uiMessages != null ? uiMessages : Collections.emptyList());

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("version", version);
            meta.put("os", os);
            meta.put("model", model != null ? model : "");
            meta.put("description", Redactor.redact(description != null ? description : ""));
            meta.put("ts", System.currentTimeMillis());
            byte[] zip = buildZip(trace, uiTrace, GSON.toJson(meta));

            if (zip.length > MAX_ZIP_BYTES) {
                return new UploadResult(false, null,
                        "诊断数据过大（" + (zip.length / 1024 / 1024) + "MB），超过 10MB 上限，请缩短会话后重试。");
            }
            return post(zip, version, os, url);
        } catch (Exception e) {
            return new UploadResult(false, null, "上传失败：" + e.getMessage());
        }
    }

    /// Packs the trace jsonl, the simplified UI-trace jsonl, and metadata json into a single
    /// in-memory zip.
    static byte[] buildZip(byte[] traceBytes, byte[] uiTraceBytes, String metaJson) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("ai-trace.jsonl"));
            zos.write(traceBytes);
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry(UiTraceBuilder.FILE_NAME));
            zos.write(uiTraceBytes);
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("meta.json"));
            zos.write(metaJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    /// POSTs the zip to {@code url} per the HMCL-AE-Site /api/feedback contract and parses {ok, id}.
    /// Honours HMCL's proxy if one is set (harmless when direct).
    static UploadResult post(byte[] zip, String version, String os, String url) throws Exception {
        // ProxyAuthenticatorHolder.configure answers proxy 407 challenges with the credentials
        // HMCL pushed down (the JDK HttpClient ignores Authenticator.setDefault).
        HttpClient client = ProxyAuthenticatorHolder.configure(HttpClient.newBuilder()
                .proxy(ProxySelector.getDefault())
                .connectTimeout(Duration.ofSeconds(15)))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/zip")
                .header("X-HMCLAE-Version", version)
                .header("X-HMCLAE-OS", os)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(zip))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            return new UploadResult(false, null, "服务器返回 " + resp.statusCode() + "：" + abbreviate(resp.body()));
        }
        try {
            JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (o.has("ok") && o.get("ok").getAsBoolean() && o.has("id")) {
                return new UploadResult(true, o.get("id").getAsString(), null);
            }
        } catch (RuntimeException ignored) {
            // fall through to the generic error below
        }
        return new UploadResult(false, null, "服务器响应异常：" + abbreviate(resp.body()));
    }

    private static String abbreviate(@Nullable String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
