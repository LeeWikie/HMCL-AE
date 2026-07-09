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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for {@link HttpRetryClassifier} — the shared retryable-status table extracted from
/// the chat adapter (borrow-list A5) so the search/HTTP tool layer can reuse it.
public final class HttpRetryClassifierTest {

    @Test
    public void retryableStatusTable() {
        // Retryable: network-unknown, rate limit, server-side.
        for (int s : new int[]{0, 429, 500, 502, 503, 504}) {
            assertTrue(HttpRetryClassifier.isRetryableStatus(s), s + " must be retryable");
        }
        // Not retryable: client errors and anything unclassified.
        for (int s : new int[]{400, 401, 403, 404, 418, 501}) {
            assertFalse(HttpRetryClassifier.isRetryableStatus(s), s + " must NOT be retryable");
        }
    }

    @Test
    public void categorizeSplitsAuthTransientAndUnclassified() {
        assertEquals(HttpRetryClassifier.Category.AUTH_REJECTED, HttpRetryClassifier.categorize(401));
        assertEquals(HttpRetryClassifier.Category.AUTH_REJECTED, HttpRetryClassifier.categorize(403));
        assertEquals(HttpRetryClassifier.Category.TRANSIENT, HttpRetryClassifier.categorize(429));
        assertEquals(HttpRetryClassifier.Category.TRANSIENT, HttpRetryClassifier.categorize(503));
        assertEquals(HttpRetryClassifier.Category.TRANSIENT, HttpRetryClassifier.categorize(0));
        assertEquals(HttpRetryClassifier.Category.UNCLASSIFIED, HttpRetryClassifier.categorize(400));
        assertEquals(HttpRetryClassifier.Category.UNCLASSIFIED, HttpRetryClassifier.categorize(404));
    }

    @Test
    public void extractStatusWalksLangchain4jExceptionHierarchy() {
        assertEquals(404, HttpRetryClassifier.extractStatus(
                new dev.langchain4j.exception.HttpException(404, "not found")));
        assertEquals(429, HttpRetryClassifier.extractStatus(
                new dev.langchain4j.exception.RateLimitException("slow down")));
        assertEquals(401, HttpRetryClassifier.extractStatus(
                new dev.langchain4j.exception.AuthenticationException("bad key")));
        // Walks the cause chain: a wrapped HttpException is still found.
        assertEquals(503, HttpRetryClassifier.extractStatus(
                new RuntimeException("wrapper",
                        new dev.langchain4j.exception.HttpException(503, "unavailable"))));
    }

    @Test
    public void extractStatusFallsBackToZeroForUnknownFailures() {
        assertEquals(0, HttpRetryClassifier.extractStatus(new RuntimeException("connection reset")));
        assertEquals(0, HttpRetryClassifier.extractStatus(null));
    }
}
