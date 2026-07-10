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
package org.jackhuang.hmcl.ai.net;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Single source of truth for "is this HTTP failure worth retrying" (borrow-list A5).
///
/// This classification originally lived as private helpers inside
/// {@code LangChain4jChatAdapter} (LLM-request retry) while the search/HTTP tools each hand-rolled
/// (or skipped) their own — the same codebase classified the same class of problem in one layer
/// and not the other. Extracted here so every HTTP-speaking consumer (the chat adapter now;
/// {@code WebSearchTool}/MCP wiring in a later batch) shares one table.
///
/// Status `0` means "no HTTP status could be extracted" — a connection/timeout/DNS failure,
/// which is genuinely retryable.
@NotNullByDefault
public final class HttpRetryClassifier {

    private HttpRetryClassifier() {
    }

    /// Coarse three-way classification for tool-layer failure envelopes (rewrite #15):
    /// auth/key problems are terminal, transient problems are retry-later, anything else is
    /// treated as non-retryable unless the caller has a specific reason to believe otherwise.
    public enum Category {
        /// 401/403 — the provider rejected the credentials; retrying the same call cannot succeed.
        AUTH_REJECTED,
        /// 0 (network) / 429 / 5xx — safe to retry once after a short wait.
        TRANSIENT,
        /// Anything else (including other 4xx) — treat as non-retryable by default.
        UNCLASSIFIED
    }

    /// Whether a failure with this HTTP status is worth a transparent retry: rate limits (429),
    /// server-side errors (500/502/503/504), and connection/timeout failures (status 0, no HTTP
    /// response). Client errors (400/401/403/404) are NOT retried — they won't succeed on a
    /// re-send.
    public static boolean isRetryableStatus(int status) {
        return status == 0 || status == 429
                || status == 500 || status == 502 || status == 503 || status == 504;
    }

    /// See {@link Category}.
    public static Category categorize(int status) {
        if (status == 401 || status == 403) {
            return Category.AUTH_REJECTED;
        }
        return isRetryableStatus(status) ? Category.TRANSIENT : Category.UNCLASSIFIED;
    }

    /// Best-effort HTTP status from langchain4j's exception hierarchy (walking the cause chain,
    /// bounded). langchain4j throws its OWN exception types, not HMCL's {@code LlmException}, so
    /// without this every failure looked like status 0 and the retry allowlist was dead (every
    /// pre-token error, even a 401/400, got retried). Returns `0` for unknown/network failures
    /// (genuinely retryable).
    public static int extractStatus(@Nullable Throwable error) {
        Throwable t = error;
        for (int i = 0; t != null && i < 10; i++, t = t.getCause()) {
            if (t instanceof dev.langchain4j.exception.HttpException http) {
                return http.statusCode();
            }
            if (t instanceof dev.langchain4j.exception.AuthenticationException) return 401;
            if (t instanceof dev.langchain4j.exception.RateLimitException) return 429;
            if (t instanceof dev.langchain4j.exception.ModelNotFoundException) return 404;
            if (t instanceof dev.langchain4j.exception.InvalidRequestException) return 400;
            if (t instanceof dev.langchain4j.exception.InternalServerException) return 500;
        }
        return 0;
    }
}
