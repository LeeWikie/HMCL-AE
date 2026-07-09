package org.jackhuang.hmcl.ai.diagnostic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.ai.trace.TraceRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies the diagnostic upload seam end-to-end without a real server: the zip packs the trace
/// + metadata, and post() speaks the /api/feedback contract (headers + {ok,id}) against a local
/// fake HttpServer. This is the client half of the trace upload loop.
public class DiagnosticUploaderTest {

    @AfterEach
    public void resetTraceRecorder() {
        TraceRecorder.configure(null, false);
    }

    @Test
    public void buildZipPacksTraceAndMeta() throws Exception {
        byte[] zip = DiagnosticUploader.buildZip(
                "{\"type\":\"request\"}\n".getBytes(StandardCharsets.UTF_8),
                "{\"type\":\"user\",\"text\":\"hi\"}\n".getBytes(StandardCharsets.UTF_8),
                "{\"version\":\"0.4.0-alpha\"}");

        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                entries.put(e.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertTrue(entries.containsKey("ai-trace.jsonl"));
        assertTrue(entries.containsKey("ui-trace.jsonl"), "simplified UI-visible trace must be packed too");
        assertTrue(entries.containsKey("meta.json"));
        assertTrue(entries.get("ai-trace.jsonl").contains("request"));
        assertTrue(entries.get("ui-trace.jsonl").contains("hi"));
        assertTrue(entries.get("meta.json").contains("0.4.0-alpha"));
    }

    @Test
    public void postSpeaksTheFeedbackContract() throws Exception {
        AtomicReference<String> seenVersion = new AtomicReference<>();
        AtomicReference<Integer> bodyLen = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/feedback", exchange -> {
            seenVersion.set(exchange.getRequestHeaders().getFirst("X-HMCLAE-Version"));
            bodyLen.set(exchange.getRequestBody().readAllBytes().length);
            byte[] resp = "{\"ok\":true,\"id\":\"F-TEST01\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/feedback";
            byte[] zip = DiagnosticUploader.buildZip("trace".getBytes(StandardCharsets.UTF_8),
                    "ui-trace".getBytes(StandardCharsets.UTF_8), "{}");

            DiagnosticUploader.UploadResult r = DiagnosticUploader.post(zip, "0.4.0-alpha", "Windows", url);

            assertTrue(r.ok(), () -> "expected ok, got: " + r.error());
            assertEquals("F-TEST01", r.id(), "short reference code returned");
            assertEquals("0.4.0-alpha", seenVersion.get(), "version header sent");
            assertEquals(zip.length, bodyLen.get(), "full zip body sent");
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void postSurfacesServerErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/feedback", exchange -> {
            byte[] resp = "{\"error\":\"busy\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/feedback";
            DiagnosticUploader.UploadResult r = DiagnosticUploader.post(
                    "z".getBytes(StandardCharsets.UTF_8), "v", "os", url);
            assertFalse(r.ok());
            assertNotNull(r.error());
            assertTrue(r.error().contains("503"), "server status surfaced to the user");
        } finally {
            server.stop(0);
        }
    }

    /// Regression guard: before {@link DiagnosticUploader#upload} went through
    /// {@link TraceRecorder#readTraceFile}, it read the trace file with a bare, unsynchronized
    /// {@code Files.readAllBytes} — racing {@link TraceRecorder#record}'s own synchronized
    /// append-write for the SAME session (e.g. a chat turn still streaming in the background while
    /// the user clicks "上传诊断信息"). A real background thread keeps appending trace events via
    /// {@code record} while the main thread repeatedly calls {@code upload} concurrently; every
    /// uploaded trace must split into complete, individually-parseable JSON lines — never a
    /// torn/truncated final line — because the read and the write now share one lock.
    @Test
    public void uploadNeverPacksATornTraceLineWhileTraceRecorderIsStillAppending(@TempDir Path dir) throws Exception {
        TraceRecorder.configure(dir, true);
        String sessionId = "race-session";

        List<String> capturedTraces = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/feedback", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(body))) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if ("ai-trace.jsonl".equals(e.getName())) {
                        capturedTraces.add(new String(zis.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
            byte[] resp = "{\"ok\":true,\"id\":\"F-RACE\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();

        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/feedback";
            Path traceFile = TraceRecorder.traceFile(sessionId);

            // ~2KB payload per event; 300 events keeps the whole trace well under the 10MB zip cap
            // even though it grows on every iteration.
            String bigPayload = "x".repeat(2000);
            Thread writer = new Thread(() -> {
                for (int i = 0; i < 300; i++) {
                    JsonObject event = new JsonObject();
                    event.addProperty("type", "note");
                    event.addProperty("i", i);
                    event.addProperty("payload", bigPayload);
                    TraceRecorder.record(sessionId, event);
                }
            }, "trace-writer-race-test");
            writer.start();

            List<DiagnosticUploader.UploadResult> results = new ArrayList<>();
            while (writer.isAlive()) {
                results.add(DiagnosticUploader.upload(traceFile, "v", "os", null, null, null, url));
            }
            writer.join(10_000);
            // One more read once the writer is fully done, for good measure.
            results.add(DiagnosticUploader.upload(traceFile, "v", "os", null, null, null, url));

            for (DiagnosticUploader.UploadResult r : results) {
                assertTrue(r.ok(), () -> "upload should always succeed: " + r.error());
            }
            assertFalse(capturedTraces.isEmpty(), "the race window should have produced at least one upload");

            for (String trace : capturedTraces) {
                for (String line : trace.split("\n")) {
                    if (line.isBlank()) continue;
                    final String theLine = line;
                    assertDoesNotThrow(() -> JsonParser.parseString(theLine).getAsJsonObject(),
                            () -> "torn/unparseable trace line captured mid-write: " + theLine);
                }
            }
        } finally {
            server.stop(0);
        }
    }
}
