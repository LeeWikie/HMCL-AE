/*
 * Hello Minecraft! Launcher - Agent Experience
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
package org.jackhuang.hmcl.ui.ai.tools.ocr;

import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/// Small HTTP helper for OCR clients. Uses HMCL's native {@link NetworkUtils} so the
/// user's globally-configured proxy / timeouts / User-Agent are honoured (instead of a
/// raw {@code HttpURLConnection}).
@NotNullByDefault
final class OcrHttp {

    private OcrHttp() {
    }

    /// Sends a POST with the given body, content type and extra headers, returning the
    /// response body. On a non-2xx status the response/error body is included in the
    /// thrown exception so callers can surface a useful message.
    static String post(String url, String contentType, byte[] body, Map<String, String> headers) throws IOException {
        HttpURLConnection conn = NetworkUtils.createHttpConnection(URI.create(url));
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", contentType);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }

        int status = conn.getResponseCode();
        String text = readBody(status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + (text.isEmpty() ? "" : ": " + truncate(text)));
        }
        return text;
    }

    static String postJson(String url, String json, Map<String, String> headers) throws IOException {
        return post(url, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8), headers);
    }

    static String postForm(String url, String form, Map<String, String> headers) throws IOException {
        return post(url, "application/x-www-form-urlencoded; charset=utf-8",
                form.getBytes(StandardCharsets.UTF_8), headers);
    }

    private static String readBody(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}
