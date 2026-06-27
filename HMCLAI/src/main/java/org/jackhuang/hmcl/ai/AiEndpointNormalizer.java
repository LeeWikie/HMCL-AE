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
package org.jackhuang.hmcl.ai;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;

/// Normalizes user-supplied endpoint strings into full, valid URLs.
///
/// ## Normalization rules
///
/// 1. If the input is `null` or blank, the result is `null`.
/// 2. If the input already contains a scheme (`http://` or `https://`), the
///    full URL is preserved and no scheme guessing is done.
/// 3. If no scheme is present, it defaults to `https://`, **unless** the host
///    looks local (`127.*`, `192.*`, `localhost`), in which case `http://` is used.
/// 4. If the protocol family carries a default path suffix (e.g. `/v1/chat/completions`
///    for OpenAI) and the user-supplied endpoint has no path or only a `/` path,
///    the suffix is appended.
/// 5. Trailing slashes are stripped before appending the path suffix so the
///    result never contains double slashes.
///
/// ## Example
///
/// | Input                    | Family              | Output
/// |--------------------------|---------------------|--------------------------------------
/// | `api.openai.com`         | `openai-completions`| `https://api.openai.com/v1/chat/completions`
/// | `localhost:11434`        | `openai-completions`| `http://localhost:11434/v1/chat/completions`
/// | `https://api.openai.com/v1/chat/completions` | `openai-completions` | (unchanged)
/// | `127.0.0.1:8080/v1`     | `anthropic`         | `http://127.0.0.1:8080/v1/messages`
/// | `http://myserver/`       | `restapi`           | `http://myserver/`
@NotNullByDefault
public final class AiEndpointNormalizer {

    private AiEndpointNormalizer() {
        // utility class
    }

    /// Normalizes the given endpoint string according to the protocol family.
    ///
    /// @param rawEndpoint    the raw endpoint as typed by the user; may be `null`
    /// @param protocolFamily the protocol family id (see {@link AiProtocolFamily#getId()})
    /// @return the normalized endpoint URL, or `null` if the input is blank
    @Nullable
    public static String normalize(@Nullable String rawEndpoint, String protocolFamily) {
        if (rawEndpoint == null || rawEndpoint.isBlank()) {
            return null;
        }

        String input = rawEndpoint.trim();
        boolean hasScheme = input.startsWith("http://") || input.startsWith("https://");

        String scheme;
        String hostAndPath;
        if (hasScheme) {
            int schemeEnd = input.indexOf("://") + 3;
            scheme = input.substring(0, schemeEnd);
            hostAndPath = input.substring(schemeEnd);
        } else {
            hostAndPath = input;
            scheme = isLocalHost(hostAndPath) ? "http://" : "https://";
        }

        int slashIdx = hostAndPath.indexOf('/');
        String host = slashIdx >= 0 ? hostAndPath.substring(0, slashIdx) : hostAndPath;
        String path = slashIdx >= 0 ? hostAndPath.substring(slashIdx) : "";

        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        AiProtocolFamily family = AiProtocolFamily.fromId(protocolFamily);
        String suffix = family != null ? family.getDefaultPathSuffix() : null;
        if (suffix != null && (path.isEmpty() || path.equals("/"))) {
            path = suffix;
        }

        String normalized = scheme + host + path;

        try {
            new URI(normalized);
        } catch (URISyntaxException e) {
            return scheme + host;
        }

        return normalized;
    }

    /// Returns `true` when the host looks like a local/loopback address that
    /// should default to `http://` instead of `https://`.
    private static boolean isLocalHost(String hostAndPath) {
        // Strip any port and path to isolate the host
        String host = hostAndPath;
        int slashIdx = host.indexOf('/');
        if (slashIdx >= 0) {
            host = host.substring(0, slashIdx);
        }
        int colonIdx = host.lastIndexOf(':');
        if (colonIdx >= 0 && colonIdx > host.indexOf(']')) {
            // IPv6 like [::1]:8080
            host = host.substring(0, colonIdx);
        }

        return "localhost".equalsIgnoreCase(host)
                || host.startsWith("127.")
                || host.startsWith("192.168.")
                || host.startsWith("10.")
                || host.startsWith("172.16.")
                || host.startsWith("172.17.")
                || host.startsWith("172.18.")
                || host.startsWith("172.19.")
                || host.startsWith("172.20.")
                || host.startsWith("172.21.")
                || host.startsWith("172.22.")
                || host.startsWith("172.23.")
                || host.startsWith("172.24.")
                || host.startsWith("172.25.")
                || host.startsWith("172.26.")
                || host.startsWith("172.27.")
                || host.startsWith("172.28.")
                || host.startsWith("172.29.")
                || host.startsWith("172.30.")
                || host.startsWith("172.31.")
                || host.equals("0.0.0.0")
                || host.startsWith("[::1]");
    }
}
