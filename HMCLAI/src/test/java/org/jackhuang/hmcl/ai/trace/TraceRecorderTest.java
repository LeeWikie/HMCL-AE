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
    public void redactsSecretsNestedInsideAJsonShapedStringValue(@TempDir Path dir) throws Exception {
        // A tool's raw arguments/result are stored as an ALREADY-JSON string property (see
        // TraceEvents.tool()/toolCallsJson(): event.addProperty("result", rawJsonString)). When
        // Gson serializes the OUTER JsonObject, it double-escapes the inner JSON's quotes (`"`
        // becomes `\"` in the final line) — at that point neither Redactor pattern (both require a
        // LITERAL `"` around the key/value) can match a secret nested one level deep. This mirrors
        // redactsSecretsInEventPayload above but with one extra level of JSON nesting.
        TraceRecorder.configure(dir, true);
        JsonObject e = event("tool");
        e.addProperty("name", "some_mcp_tool");
        e.addProperty("result", "{\"accessToken\":\"eyJhbGciOISECRETVALUE0123456789\"}");
        TraceRecorder.record("s-nested", e);

        String content = Files.readString(TraceRecorder.traceFile("s-nested"));
        assertFalse(content.contains("eyJhbGciOISECRETVALUE"),
                "secret nested inside a JSON-shaped string value must be scrubbed: " + content);
        assertTrue(content.contains("[REDACTED]"));
        assertTrue(content.contains("some_mcp_tool"), "non-secret fields must survive untouched");
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
    public void readTraceFileReturnsExactlyWhatWasWritten(@TempDir Path dir) throws Exception {
        TraceRecorder.configure(dir, true);
        TraceRecorder.record("s", event("request"));
        TraceRecorder.record("s", event("response"));

        Path f = TraceRecorder.traceFile("s");
        byte[] viaSnapshot = TraceRecorder.readTraceFile(f);
        byte[] viaPlainRead = Files.readAllBytes(f);
        assertArrayEquals(viaPlainRead, viaSnapshot, "snapshot must match the file's actual bytes");
    }

    @Test
    public void readTraceFileReturnsEmptyArrayForNullOrMissingFile(@TempDir Path dir) throws Exception {
        assertEquals(0, TraceRecorder.readTraceFile(null).length, "null path -> empty, not an exception");
        assertEquals(0, TraceRecorder.readTraceFile(dir.resolve("never-written.jsonl")).length,
                "nonexistent file -> empty, not an exception");
    }

    @Test
    public void sanitizesSessionIdIntoFileName() {
        assertEquals("a_b_c", TraceRecorder.sanitize("a/b:c"));
        assertEquals("019f-abcd_ef", TraceRecorder.sanitize("019f-abcd/ef"));
    }

    @Test
    public void sanitizeNeutralizesAllDotSegments() {
        // The allow-list keeps '.' unchanged, so a value that collapses to exactly ".." or "."
        // must not survive as-is — it would be a path-traversal segment via Path.resolve.
        assertEquals("_", TraceRecorder.sanitize(".."));
        assertEquals("_", TraceRecorder.sanitize("."));
        assertNotEquals("..", TraceRecorder.sanitize("!!"));
    }

    @Test
    public void resolveToolOutputFileIsNullWhenTracingDisabled(@TempDir Path dir) {
        // Disabling the trace toggle must also stop full tool output from being offloaded to
        // disk — not just stop the .jsonl trace itself.
        TraceRecorder.configure(dir, false);
        assertNull(TraceRecorder.resolveToolOutputFile("sess-1", "turn-1", "search", 0, 0),
                "disabled tracing must not offload tool output to disk even when dir is non-null");
    }

    /// Regression guard: a cycle can request the SAME tool name more than once (e.g. several
    /// `search` calls batched into one response). Before callIndex was added, both calls'
    /// offloaded-output paths were identical ("<cycle>-<toolName>.txt"), so whichever call's write
    /// landed second silently clobbered the first's file — and with the tool-execution loop now
    /// able to run such a batch CONCURRENTLY, that collision would race instead of just
    /// last-write-wins. Distinct callIndex values (same tool, same cycle) must never collide.
    @Test
    public void toolOutputPathsAreUniquePerCallIndexEvenForTheSameToolAndCycle(@TempDir Path dir) {
        TraceRecorder.configure(dir, true);
        Path first = TraceRecorder.resolveToolOutputFile("sess-x", "turn-1", "search", 3, 0);
        Path second = TraceRecorder.resolveToolOutputFile("sess-x", "turn-1", "search", 3, 1);
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second, "same tool name + same cycle, different callIndex, must not collide");
    }

    /// Regression guard for the cross-turn path collision: `cycle` and `callIndex` both reset to 0
    /// at the start of EVERY new turn in the same session, but the output directory used to be keyed
    /// only by sessionId (stable across the whole multi-turn session). So an earlier turn's first
    /// tool call in its first cycle, and a LATER turn's first call in ITS first cycle, would resolve
    /// to the identical path whenever they happened to share a tool name — the later turn's write
    /// silently clobbering the earlier turn's still-referenced offloaded file. Two different turnIds
    /// with the same session/cycle/callIndex/toolName must never collide.
    @Test
    public void toolOutputPathsAreUniquePerTurnIdEvenForTheSameSessionCycleAndCallIndex(@TempDir Path dir) {
        TraceRecorder.configure(dir, true);
        Path turnA = TraceRecorder.resolveToolOutputFile("sess-x", "turn-a", "search", 0, 0);
        Path turnB = TraceRecorder.resolveToolOutputFile("sess-x", "turn-b", "search", 0, 0);
        assertNotNull(turnA);
        assertNotNull(turnB);
        assertNotEquals(turnA, turnB, "same session + cycle + callIndex + tool name, different turnId, must not collide");
    }
}
