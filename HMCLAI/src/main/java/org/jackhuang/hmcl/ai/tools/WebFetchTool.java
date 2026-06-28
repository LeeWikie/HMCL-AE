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
package org.jackhuang.hmcl.ai.tools;

import org.jetbrains.annotations.NotNullByDefault;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/// Fetches a URL over HTTP(S) and returns the response body as text. Useful for reading
/// install instructions / READMEs (e.g. a skill manifest or an MCP setup page) before
/// acting on them. Read-only.
@NotNullByDefault
public final class WebFetchTool implements ToolSpec {

    private static final int MAX_CHARS = 24_000;

    @Override
    public ToolPermission getPermission() {
        return ToolPermission.READ_ONLY;
    }

    @Override
    public ToolSource getSource() {
        return ToolSource.SEARCH;
    }

    @Override
    public String getName() {
        return "web_fetch";
    }

    @Override
    public String getDescription() {
        return "Fetch a URL over HTTP(S) and return its body as text. Pass 'url'. "
                + "Use it to read install instructions, READMEs or manifests before acting.";
    }

    @Override
    public boolean supportsStructuredSchema() {
        return true;
    }

    @Override
    public String getInputSchemaJson() {
        return """
               {
                 "$schema": "https://json-schema.org/draft/2020-12/schema",
                 "type": "object",
                 "properties": {
                   "url": {
                     "type": "string",
                     "description": "The absolute http(s) URL to fetch."
                   }
                 },
                 "required": ["url"]
               }
               """;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object raw = parameters.get("url");
        if (raw == null) raw = parameters.get("query");
        String url = raw == null ? "" : raw.toString().trim();
        if (url.isEmpty()) {
            return ToolResult.failure("No 'url' provided.");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.failure("Only http(s) URLs are supported: " + url);
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(25))
                    .header("User-Agent", "HMCL-AE")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            if (body == null) body = "";
            if (body.length() > MAX_CHARS) {
                body = body.substring(0, MAX_CHARS) + "\n…(truncated)";
            }
            return ToolResult.success("HTTP " + response.statusCode() + "\n" + body);
        } catch (java.io.IOException e) {
            return ToolResult.failure("Fetch failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Interrupted while fetching.");
        } catch (RuntimeException e) {
            return ToolResult.failure("Invalid URL: " + e.getMessage());
        }
    }
}
