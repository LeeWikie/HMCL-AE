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

/// Simple Tavily API search client.
@NotNullByDefault
public final class TavilySearchClient implements SearchClient {

    private static final Gson GSON = new Gson();
    private final String apiKey;
    private final String endpoint;

    public TavilySearchClient(String endpoint, String apiKey) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
    }

    @Override
    public SearchResponse search(String query, int maxResults) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String body = GSON.toJson(Map.of(
                "api_key", apiKey,
                "query", query,
                "max_results", maxResults,
                "search_depth", "basic"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Tavily returned " + response.statusCode() + ": " + response.body());
        }

        Map<String, Object> data = GSON.fromJson(response.body(),
                new TypeToken<Map<String, Object>>(){}.getType());
        List<SearchResult> results = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawResults = (List<Map<String, Object>>) data.getOrDefault("results", List.of());
        for (Map<String, Object> r : rawResults) {
            results.add(new SearchResult(
                    String.valueOf(r.getOrDefault("title", "")),
                    String.valueOf(r.getOrDefault("url", "")),
                    String.valueOf(r.getOrDefault("content", ""))));
        }
        return new SearchResponse(results, "tavily");
    }
}
