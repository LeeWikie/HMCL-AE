package org.jackhuang.hmcl.ai.search;

import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSource;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Map;
import java.util.stream.Collectors;

@NotNullByDefault
public final class WebSearchTool implements ToolSpec {

    private final AiSearchConfig config;

    public WebSearchTool(AiSearchConfig config) {
        this.config = config;
    }

    @Override
    public ToolPermission getPermission() {
        return ToolPermission.EXTERNAL_NETWORK;
    }

    @Override
    public ToolSource getSource() {
        return ToolSource.SEARCH;
    }

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "Search the web for current information. Pass 'query' (search terms)."
                + " Returns titles, URLs, and snippets with sources.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        if (!config.isEnabled()) {
            return ToolResult.failure("Web search is disabled in AI settings.");
        }

        String query = parameters.getOrDefault("query", "").toString().trim();
        if (query.isEmpty()) {
            return ToolResult.failure("No search query provided.");
        }
        int maxResults = parse(parameters.get("maxResults"), config.getMaxResults());

        SearchClient client = buildClient();
        if (client == null) {
            return ToolResult.failure("No search client configured for provider: " + config.getProvider());
        }

        try {
            SearchResponse resp = client.search(query, maxResults);
            String formatted = resp.results().stream()
                    .map(r -> "[" + r.title() + "](" + r.url() + ")\n" + r.snippet())
                    .collect(Collectors.joining("\n\n"));
            return ToolResult.success(formatted.isEmpty() ? "(no results)" : formatted);
        } catch (Exception e) {
            return ToolResult.failure("Search error: " + e.getMessage());
        }
    }

    private SearchClient buildClient() {
        String provider = config.getProvider();
        String endpoint = config.getEndpoint();
        String apiKey = config.getApiKey();
        if (provider == null) return null;

        return switch (provider.toLowerCase()) {
            case "tavily" -> new TavilySearchClient(endpoint, apiKey);
            case "searxng" -> new SearxngSearchClient(endpoint, apiKey);
            default -> null;
        };
    }

    private static int parse(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
