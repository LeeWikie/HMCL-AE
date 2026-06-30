package org.jackhuang.hmcl.ai.search;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNullByDefault;
import com.google.gson.annotations.SerializedName;

@NotNullByDefault
public final class AiSearchConfig {

    // Default to Bocha: a China-reachable search API (no proxy needed on the mainland), better for
    // the target audience than Tavily (US, requires a proxy). Bocha's endpoint is fixed, so the
    // user only needs to supply an API key.
    @SerializedName("provider")
    private String provider = "bocha";

    @SerializedName("endpoint")
    private String endpoint = "https://api.tavily.com/search";

    @SerializedName("apiKey")
    private String apiKey = "";

    @SerializedName("enabled")
    private boolean enabled = false;

    @SerializedName("maxResults")
    private int maxResults = 5;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
}
