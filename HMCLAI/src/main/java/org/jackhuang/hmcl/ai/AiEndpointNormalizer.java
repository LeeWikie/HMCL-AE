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

        if ("localhost".equalsIgnoreCase(host) || "0.0.0.0".equals(host)
                || "[::1]".equals(host) || "::1".equals(host)) {
            return true;
        }

        int[] octets = parseIPv4(host);
        if (octets == null) {
            return false; // not a bare IPv4 literal — never classify an arbitrary hostname as local
        }
        if (octets[0] == 127) return true; // 127.0.0.0/8
        if (octets[0] == 10) return true; // 10.0.0.0/8
        if (octets[0] == 192 && octets[1] == 168) return true; // 192.168.0.0/16
        return octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31; // 172.16.0.0/12
    }

    /// Parses {@code host} as a strict dotted-quad IPv4 literal (exactly 4 numeric octets, each
    /// 0-255, no extra characters). Deliberately NOT a string-prefix check: a public hostname that
    /// merely BEGINS with digits resembling a private-range prefix (e.g. a "magic DNS" name like
    /// `10.0.0.1.attacker.example` that really resolves to an attacker-chosen public IP) must never
    /// be misclassified as local — that would silently downgrade the request to plaintext `http://`,
    /// exposing the Bearer API key to network interception for what is actually a remote server.
    @Nullable
    private static int[] parseIPv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                return null;
            }
            for (int j = 0; j < part.length(); j++) {
                if (!Character.isDigit(part.charAt(j))) {
                    return null;
                }
            }
            int value;
            try {
                value = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return null;
            }
            if (value < 0 || value > 255) {
                return null;
            }
            octets[i] = value;
        }
        return octets;
    }
}
