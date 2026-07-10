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
import org.jackhuang.hmcl.ai.net.ProxyAuthenticatorHolder;
import org.jetbrains.annotations.NotNullByDefault;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Zhipu (智谱) web-search client. China-reachable (open.bigmodel.cn, no proxy needed on the
/// mainland). Fixed endpoint; the only configuration is the API key. Uses the cheapest basic
/// engine (search_std). Honours HMCL's proxy if one is set (harmless when direct).
///
/// Request:  POST /api/paas/v4/web_search, Authorization: Bearer &lt;key&gt;,
///           body {search_engine, search_query, count}.
/// Response: {search_result:[{title, link, content, media, ...}]}.
@NotNullByDefault
public final class ZhipuSearchClient implements SearchClient {

    private static final Gson GSON = new Gson();
    private static final String ENDPOINT = "https://open.bigmodel.cn/api/paas/v4/web_search";

    private final String apiKey;

    public ZhipuSearchClient(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public SearchResponse search(String query, int maxResults) throws Exception {
        // ProxyAuthenticatorHolder.configure answers proxy 407 challenges with the credentials
        // HMCL pushed down (the JDK HttpClient ignores Authenticator.setDefault).
        HttpClient client = ProxyAuthenticatorHolder.configure(HttpClient.newBuilder()
                .proxy(java.net.ProxySelector.getDefault())
                .connectTimeout(Duration.ofSeconds(10)))
                .build();

        int count = Math.max(1, Math.min(50, maxResults));
        String body = GSON.toJson(Map.of(
                "search_engine", "search_std",
                "search_query", query,
                "count", count));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("智谱搜索返回 " + response.statusCode() + ": " + response.body());
        }
        return parseResults(response.body());
    }

    /// Parses a Zhipu web-search JSON response: the {@code search_result} array of
    /// {@code {title, link, content, ...}} items. Package-private for unit testing without a network call.
    static SearchResponse parseResults(String json) {
        List<SearchResult> results = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return new SearchResponse(results, "zhipu");
        }
        Map<String, Object> root = GSON.fromJson(json, new TypeToken<Map<String, Object>>() {}.getType());
        if (root == null) {
            return new SearchResponse(results, "zhipu");
        }
        Object listObj = root.get("search_result");
        if (listObj instanceof List) {
            for (Object item : (List<?>) listObj) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<?, ?> m = (Map<?, ?>) item;
                Object title = m.get("title");
                Object link = m.get("link");
                Object content = m.get("content");
                results.add(new SearchResult(
                        title != null ? String.valueOf(title) : "",
                        link != null ? String.valueOf(link) : "",
                        content != null ? String.valueOf(content) : ""));
            }
        }
        return new SearchResponse(results, "zhipu");
    }
}
