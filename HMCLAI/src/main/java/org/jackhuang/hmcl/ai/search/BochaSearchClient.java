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
package org.jackhuang.hmcl.ai.search;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.jetbrains.annotations.NotNullByDefault;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Bocha (博查) web-search client. Bocha is a China-reachable search API (no proxy needed on the
/// mainland), which makes it a good default for the target audience. The endpoint is fixed, so the
/// only configuration is the API key. Honours HMCL's proxy if one is set (harmless when direct).
///
/// Request:  POST https://api.bochaai.com/v1/web-search, Authorization: Bearer &lt;key&gt;,
///           body {query, count, summary, freshness}.
/// Response: {code, data:{webPages:{value:[{name,url,snippet,summary}]}}} (Bing-style envelope).
@NotNullByDefault
public final class BochaSearchClient implements SearchClient {

    private static final Gson GSON = new Gson();
    private static final String ENDPOINT = "https://api.bochaai.com/v1/web-search";

    private final String apiKey;

    public BochaSearchClient(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public SearchResponse search(String query, int maxResults) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .proxy(java.net.ProxySelector.getDefault())
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String body = GSON.toJson(Map.of(
                "query", query,
                "count", Math.max(1, maxResults),
                "summary", true,
                "freshness", "noLimit"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Bocha 搜索返回 " + response.statusCode() + ": " + response.body());
        }

        Map<String, Object> root = GSON.fromJson(response.body(),
                new TypeToken<Map<String, Object>>() {}.getType());
        List<SearchResult> results = new ArrayList<>();
        if (root == null) {
            return new SearchResponse(results, "bocha");
        }
        // Bocha wraps results in {code,data:{webPages:{value:[...]}}}; tolerate a flatter shape too.
        Object dataObj = root.get("data");
        Map<?, ?> data = dataObj instanceof Map ? (Map<?, ?>) dataObj : root;
        Object webPagesObj = data.get("webPages");
        if (webPagesObj instanceof Map) {
            Object valueObj = ((Map<?, ?>) webPagesObj).get("value");
            if (valueObj instanceof List) {
                for (Object item : (List<?>) valueObj) {
                    if (!(item instanceof Map)) {
                        continue;
                    }
                    Map<?, ?> m = (Map<?, ?>) item;
                    Object name = m.get("name");
                    Object url = m.get("url");
                    Object summary = m.get("summary");
                    Object snippet = summary != null ? summary : m.get("snippet");
                    results.add(new SearchResult(
                            name != null ? String.valueOf(name) : "",
                            url != null ? String.valueOf(url) : "",
                            snippet != null ? String.valueOf(snippet) : ""));
                }
            }
        }
        return new SearchResponse(results, "bocha");
    }
}
