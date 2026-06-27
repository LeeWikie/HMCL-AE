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
package org.jackhuang.hmcl.ai.llm;

import org.jetbrains.annotations.NotNullByDefault;

/// Exception thrown when an LLM API request fails.
///
/// Carries the HTTP [statusCode] so callers can distinguish between
/// authentication failures (401), rate limits (429), server errors (5xx),
/// and network-level failures (status code 0).
@NotNullByDefault
public final class LlmException extends Exception {

    /// The HTTP status code, or 0 for non-HTTP errors such as network failures.
    private final int statusCode;

    /// Creates an exception with a message and HTTP status code.
    ///
    /// @param message    a human-readable description of the error
    /// @param statusCode the HTTP status code, or 0 for non-HTTP errors
    public LlmException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    /// Creates an exception with a message, HTTP status code, and cause.
    ///
    /// @param message    a human-readable description of the error
    /// @param statusCode the HTTP status code, or 0 for non-HTTP errors
    /// @param cause      the underlying exception that triggered this error
    public LlmException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /// Returns the HTTP status code associated with this error.
    ///
    /// A value of 0 indicates a non-HTTP error (e.g. network failure or I/O error).
    public int getStatusCode() {
        return statusCode;
    }
}
