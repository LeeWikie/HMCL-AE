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

@NotNullByDefault
public final class SearxngSearchClient implements SearchClient {

    private static final Gson GSON = new Gson();
    private final String endpoint;
    @org.jetbrains.annotations.Nullable
    private final String apiKey;

    public SearxngSearchClient(String endpoint, @org.jetbrains.annotations.Nullable String apiKey) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
    }

    @Override
    public SearchResponse search(String query, int maxResults) throws Exception {
        // Honour HMCL's globally-configured proxy (JDK HttpClient ignores the default
        // ProxySelector otherwise) and follow redirects (self-hosted instances often 301).
        // ProxyAuthenticatorHolder.configure answers proxy 407 challenges with the credentials
        // HMCL pushed down (the JDK HttpClient ignores Authenticator.setDefault).
        HttpClient client = ProxyAuthenticatorHolder.configure(HttpClient.newBuilder()
                .proxy(java.net.ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10)))
                .build();

        String uri = endpoint + "?q=" + java.net.URLEncoder.encode(query, "UTF-8")
                + "&format=json&categories=general";

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .GET()
                .timeout(Duration.ofSeconds(30));
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("SearXNG returned " + response.statusCode());
        }

        Map<String, Object> data = GSON.fromJson(response.body(),
                new TypeToken<Map<String, Object>>(){}.getType());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawResults = (List<Map<String, Object>>) data.getOrDefault("results", List.of());
        List<SearchResult> results = new ArrayList<>();
        int count = 0;
        for (Map<String, Object> r : rawResults) {
            if (count++ >= maxResults) break;
            results.add(new SearchResult(
                    String.valueOf(r.getOrDefault("title", "")),
                    String.valueOf(r.getOrDefault("url", "")),
                    String.valueOf(r.getOrDefault("content", ""))));
        }
        return new SearchResponse(results, "searxng");
    }
}
