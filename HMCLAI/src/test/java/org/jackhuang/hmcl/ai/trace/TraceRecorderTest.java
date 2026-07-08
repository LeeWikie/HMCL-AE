package org.jackhuang.hmcl.ai.trace;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Locks the trace sink contract: append-only JSONL per session, ts auto-stamped, secrets
/// redacted, and a hard no-op when disabled/unconfigured (tracing must never break a turn).
public class TraceRecorderTest {

    @AfterEach
    public void reset() {
        TraceRecorder.configure(null, false);
    }

    private static JsonObject event(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return o;
    }

    @Test
    public void recordsOneJsonlLinePerEventWithAutoTimestamp(@TempDir Path dir) throws Exception {
        TraceRecorder.configure(dir, true);
        String sid = "sess-1";
        TraceRecorder.record(sid, event("request"));
        TraceRecorder.record(sid, event("response"));

        Path f = dir.resolve("sess-1.jsonl");
        assertTrue(Files.exists(f));
        List<String> lines = Files.readAllLines(f);
        assertEquals(2, lines.size(), "append-only: one line per event");

        JsonObject first = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals("request", first.get("type").getAsString());
        assertTrue(first.has("ts"), "ts auto-stamped");
        assertTrue(first.get("ts").getAsLong() > 0);
    }

    @Test
    public void redactsSecretsInEventPayload(@TempDir Path dir) throws Exception {
        TraceRecorder.configure(dir, true);
        JsonObject e = event("tool");
        e.addProperty("args", "{\"api_key\":\"sk-ABCDEFGHIJKLMNOP0123456789\"}");
        e.addProperty("path", "D:/games/.minecraft");
        TraceRecorder.record("s", e);

        String content = Files.readString(TraceRecorder.traceFile("s"));
        assertFalse(content.contains("sk-ABCDEFGHIJKLMNOP"), "secret scrubbed from trace");
        assertTrue(content.contains("[REDACTED]"));
        assertTrue(content.contains("D:/games/.minecraft"), "path preserved for debugging");
    }

    @Test
    public void noOpWhenDisabledOrUnconfigured(@TempDir Path dir) {
        // unconfigured
        TraceRecorder.record("s", event("request"));
        assertFalse(TraceRecorder.isEnabled());

        // configured but disabled
        TraceRecorder.configure(dir, false);
        TraceRecorder.record("s", event("request"));
        assertFalse(Files.exists(dir.resolve("s.jsonl")), "disabled → no file written");

        // blank session id → no-op even when enabled
        TraceRecorder.configure(dir, true);
        TraceRecorder.record("  ", event("request"));
        assertEquals(0L, dir.toFile().list().length, "blank session id writes nothing");
    }

    @Test
    public void sanitizesSessionIdIntoFileName() {
        assertEquals("a_b_c", TraceRecorder.sanitize("a/b:c"));
        assertEquals("019f-abcd_ef", TraceRecorder.sanitize("019f-abcd/ef"));
    }
}
