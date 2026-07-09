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

    /// Regression guard: a raw trace event containing a properly JSON-quoted secret field
    /// (`{"password":"hunter2ABCDEF"}`, `{"accessToken":"…"}`) must be scrubbed too — not just the
    /// unquoted `key: value` / `key=value` shape. Before this, the closing quote right after the
    /// key broke SECRET_KEYVAL_PATTERN's match, so any JSON-shaped tool argument or API response
    /// carrying a secret (a common shape, since TraceRecorder writes whole JSON lines) reached the
    /// uploadable trace file completely unredacted.
    @Test
    public void scrubsJsonQuotedSecrets() {
        String redacted = Redactor.redact("{\"password\":\"hunter2ABCDEF\"}");
        assertFalse(redacted.contains("hunter2ABCDEF"), "quoted password value must be scrubbed");
        assertTrue(redacted.contains("\"password\":\"[REDACTED]\""));

        String tokenRedacted = Redactor.redact("{\"accessToken\":\"eyJhbGciOiJIUzI1NiJ9.abc123.def456\"}");
        assertFalse(tokenRedacted.contains("eyJhbGciOiJIUzI1NiJ9"), "quoted JWT-shaped token must be scrubbed");
        assertTrue(tokenRedacted.contains("[REDACTED]"));

        // still parseable JSON after redaction, and non-secret fields survive untouched
        JsonObject o = new JsonObject();
        o.addProperty("client_secret", "s3cr3t-value-123");
        o.addProperty("note", "just some prose");
        JsonObject back = JsonParser.parseString(Redactor.redact(o.toString())).getAsJsonObject();
        assertEquals("[REDACTED]", back.get("client_secret").getAsString());
        assertEquals("just some prose", back.get("note").getAsString());
    }

    /// Regression guard: `\b` is a WORD boundary and `_` is a word character, so a bare `token`
    /// alternative in the pattern never matched the "token" inside "refresh_token"/"client_token"
    /// (no boundary between two word characters) — exactly the field names HMCL's own account
    /// system uses for Microsoft/Yggdrasil OAuth credentials.
    @Test
    public void scrubsRefreshAndClientTokenKeys() {
        assertTrue(Redactor.redact("refresh_token: abcdef123456xyz").contains("[REDACTED]"));
        assertTrue(Redactor.redact("client_token=abcdef123456xyz").contains("[REDACTED]"));
        String jsonRedacted = Redactor.redact("{\"refreshToken\":\"eyJhbGciOiJIUzI1NiJ9.abc123.def456\"}");
        assertFalse(jsonRedacted.contains("eyJhbGciOiJIUzI1NiJ9"));
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
