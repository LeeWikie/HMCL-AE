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

import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/// Scrubs well-known secret shapes (API keys, tokens) from text before it is written to a
/// plaintext memory file OR an agent trace that may be uploaded for diagnosis.
///
/// Deliberately conservative: only matches distinctive key/token shapes and `key=value`
/// secret pairs, so ordinary prose — and the file paths / usernames that make a trace
/// actually useful to debug — survive untouched. This is the single source of truth for
/// secret redaction, shared by {@code RememberStore} (memory) and {@code TraceRecorder} (trace).
public final class Redactor {

    private Redactor() {
    }

    /// Standalone secret tokens replaced wholesale with `[REDACTED]`.
    private static final Pattern[] SECRET_TOKEN_PATTERNS = {
            Pattern.compile("(?i)\\b(?:sk|pk|rk)-[A-Za-z0-9_-]{16,}"),   // OpenAI / Stripe-style keys
            Pattern.compile("\\bghp_[A-Za-z0-9]{20,}"),                  // GitHub personal access token
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),                   // AWS access key id
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{30,}"),                // Google API key
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}"),          // Slack token
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._-]{16,}"),     // Bearer <token>
    };

    /// `key: value` / `key=value` secrets — keep the key label, redact only the value.
    ///
    /// Includes `refresh[_-]?token` / `client[_-]?token` explicitly (not just the bare `token`
    /// alternative) because `\b` is a WORD boundary and `_` is a word character — `\btoken` never
    /// matches the "token" inside "refresh_token" or "client_token" (no boundary between two word
    /// characters), and those are exactly the field names HMCL's own account system uses for
    /// Microsoft/Yggdrasil OAuth credentials.
    private static final Pattern SECRET_KEYVAL_PATTERN = Pattern.compile(
            "(?i)\\b(api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|client[_-]?token|secret[_-]?key|"
                    + "client[_-]?secret|password|passwd|pwd|token|secret)"
                    + "(\\s*[:=]\\s*)[\"']?[A-Za-z0-9._/+\\-]{6,}[\"']?");

    /// `"key":"value"` — properly JSON-quoted key/value secrets (same key set as above). The
    /// pattern above requires the key to be followed only by optional whitespace then `:`/`=`, so
    /// it can NEVER match a JSON-quoted pair like `{"password":"hunter2ABCDEF"}` — the closing
    /// quote right after the key breaks that match. Without this, any tool argument or API
    /// response shaped like `"accessToken":"…"` reached the trace file (and its diagnostic-upload
    /// path) completely unredacted. Keeps the key label, redacts only the value.
    private static final Pattern SECRET_JSON_KEYVAL_PATTERN = Pattern.compile(
            "(?i)\"(api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|client[_-]?token|secret[_-]?key|"
                    + "client[_-]?secret|password|passwd|pwd|token|secret)\""
                    + "(\\s*:\\s*)\"[^\"]{6,}\"");

    /// Replaces known secret shapes in {@code s} with `[REDACTED]`, or returns {@code s} unchanged
    /// when it is null/empty or contains no recognized secret. Safe to run over a serialized JSON
    /// line: it only rewrites the secret value inside a `"api_key":"…"` pair or a standalone token,
    /// leaving the surrounding JSON structure valid.
    @Nullable
    public static String redact(@Nullable String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String out = s;
        for (Pattern p : SECRET_TOKEN_PATTERNS) {
            out = p.matcher(out).replaceAll("[REDACTED]");
        }
        out = SECRET_KEYVAL_PATTERN.matcher(out).replaceAll(m -> m.group(1) + m.group(2) + "[REDACTED]");
        out = SECRET_JSON_KEYVAL_PATTERN.matcher(out)
                .replaceAll(m -> "\"" + m.group(1) + "\"" + m.group(2) + "\"[REDACTED]\"");
        return out;
    }
}
