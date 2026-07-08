package org.jackhuang.hmcl.ai.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Locks the shared secret-redaction rules used by both memory and the agent trace:
/// keys/tokens scrubbed, ordinary prose and debug-relevant paths preserved, JSON stays valid.
public class RedactorTest {

    @Test
    public void scrubsKnownKeyShapes() {
        assertFalse(Redactor.redact("key: sk-ABCDEFGHIJKLMNOP0123456789").contains("sk-ABCDEF"));
        assertTrue(Redactor.redact("api_key=abcdef123456").contains("[REDACTED]"));
        assertTrue(Redactor.redact("Authorization: Bearer abcdef1234567890xyz").contains("[REDACTED]"));
        assertTrue(Redactor.redact("token=ghp_ABCDEFGHIJKLMNOPQRST0123").contains("[REDACTED]"));
    }

    @Test
    public void keepsOrdinaryProseAndPaths() {
        // prose untouched
        assertEquals("装一个 sodium 光影就行", Redactor.redact("装一个 sodium 光影就行"));
        // file paths are debug-relevant and must survive (no key shape in them)
        String path = "read D:\\PCL2\\.minecraft\\mods\\create-1.20.1.jar";
        assertEquals(path, Redactor.redact(path));
    }

    @Test
    public void nullAndEmptyPassThrough() {
        assertNull(Redactor.redact(null));
        assertEquals("", Redactor.redact(""));
    }

    @Test
    public void redactingAWholeJsonLineKeepsItValidAndSparesPaths() {
        JsonObject o = new JsonObject();
        o.addProperty("api_key", "sk-ABCDEFGHIJKLMNOP0123456789");
        o.addProperty("path", "D:/games/.minecraft");
        o.addProperty("note", "just some prose");
        String line = o.toString();

        String redacted = Redactor.redact(line);
        // still parseable JSON
        JsonObject back = JsonParser.parseString(redacted).getAsJsonObject();
        assertEquals("[REDACTED]", back.get("api_key").getAsString(), "secret value scrubbed");
        assertEquals("D:/games/.minecraft", back.get("path").getAsString(), "path preserved");
        assertEquals("just some prose", back.get("note").getAsString(), "prose preserved");
    }
}
