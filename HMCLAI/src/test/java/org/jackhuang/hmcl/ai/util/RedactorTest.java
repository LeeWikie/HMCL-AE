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

    /// Trace-only masking ({@link Redactor#redactTrace}) scrubs the personal data that memory's
    /// {@link Redactor#redact} deliberately keeps: the user name in a home-dir path is replaced with
    /// {@code <user>} while the path SHAPE (drive/prefix + everything after the name) survives so the
    /// trace stays debuggable. Covers Windows, macOS and Linux home layouts.
    @Test
    public void traceMasksHomeDirUsernameButKeepsPathShape() {
        String win = Redactor.redactTrace("read C:\\Users\\Administrator\\.minecraft\\mods\\create.jar");
        assertFalse(win.contains("Administrator"), "the Windows user name must be masked: " + win);
        assertTrue(win.contains("C:\\Users\\<user>\\.minecraft\\mods\\create.jar"),
                "the rest of the path must survive for debugging: " + win);

        assertTrue(Redactor.redactTrace("/home/alice/games").contains("/home/<user>/games"));
        assertTrue(Redactor.redactTrace("/Users/bob/Library").contains("/Users/<user>/Library"));
    }

    @Test
    public void traceMasksEmailAddresses() {
        String out = Redactor.redactTrace("contact me at jane.doe@example.com please");
        assertFalse(out.contains("jane.doe@example.com"), "the email must be masked: " + out);
        assertTrue(out.contains("[EMAIL]"), "unexpected: " + out);
    }

    /// {@link Redactor#redactTrace} is a superset of {@link Redactor#redact}: it still scrubs every
    /// secret shape, and leaves JSON structurally valid.
    @Test
    public void traceRedactionStillScrubsSecretsAndKeepsJsonValid() {
        JsonObject o = new JsonObject();
        o.addProperty("api_key", "sk-ABCDEFGHIJKLMNOP0123456789");
        o.addProperty("path", "C:\\Users\\Administrator\\.minecraft");
        o.addProperty("note", "just some prose");
        JsonObject back = JsonParser.parseString(Redactor.redactTrace(o.toString())).getAsJsonObject();
        assertEquals("[REDACTED]", back.get("api_key").getAsString(), "secret still scrubbed");
        assertFalse(back.get("path").getAsString().contains("Administrator"), "user name masked in trace");
        assertTrue(back.get("path").getAsString().contains("<user>"), "path shape kept");
        assertEquals("just some prose", back.get("note").getAsString(), "prose preserved");
    }

    /// The intended divergence: memory's {@link Redactor#redact} must NOT mask the home-dir user
    /// name (paths/usernames are debug context there and memory is local-only), only the trace
    /// layer does. Guards against someone folding the PII masking back into {@code redact}.
    @Test
    public void plainRedactKeepsHomeDirUsernameForMemory() {
        String path = "C:\\Users\\Administrator\\.minecraft";
        assertEquals(path, Redactor.redact(path), "memory redaction must leave the path/user name intact");
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
