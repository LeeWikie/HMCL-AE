package org.jackhuang.hmcl.ai.search;

/// Search provider definitions with API endpoint templates and key requirements.
public enum SearchProvider {

    TAVILY   ("Tavily",               "https://api.tavily.com/search",               "tvly-", true,  true,  1000),
    ZHIPU    ("智谱 Zhipu",           "https://open.bigmodel.cn/api/paas/v4/tools",  "",      true,  true,  100),
    SEARXNG  ("SearXNG",              "",                                             "",      false, true,  0),
    EXA      ("Exa",                  "https://api.exa.ai/search",                    "",      true,  true,  100),
    BOCHA    ("Bocha",                "https://api.bochaai.com/v1/web/search",        "sk-",   true,  true,  100),
    QUERIT   ("Querit",               "",                                             "",      true,  false, 0),
    GOOGLE   ("Google Custom Search", "https://www.googleapis.com/customsearch/v1",    "",      true,  false, 100),
    BING     ("Bing Web Search",      "https://api.bing.microsoft.com/v7.0/search",    "",      true,  false, 100),
    BAIDU    ("百度 Baidu",           "",                                             "",      true,  false, 0),
    LOCAL    ("本地搜索",              "",                                             "",      false, false, 0);

    private final String displayName;
    private final String defaultEndpoint;
    private final String keyPrefix;
    private final boolean requiresApiKey;
    private final boolean hasFreeTier;
    private final int freeQuotaPerMonth;

    SearchProvider(String displayName, String defaultEndpoint, String keyPrefix,
                   boolean needsKey, boolean freeTier, int freeQuota) {
        this.displayName = displayName;
        this.defaultEndpoint = defaultEndpoint;
        this.keyPrefix = keyPrefix;
        this.requiresApiKey = needsKey;
        this.hasFreeTier = freeTier;
        this.freeQuotaPerMonth = freeQuota;
    }

    public String getDisplayName()    { return displayName; }
    public String getDefaultEndpoint(){ return defaultEndpoint; }
    public String getKeyPrefix()      { return keyPrefix; }
    public boolean requiresApiKey()   { return requiresApiKey; }
    public boolean hasFreeTier()      { return hasFreeTier; }
    public int getFreeQuotaPerMonth() { return freeQuotaPerMonth; }

    public static SearchProvider fromId(String id) {
        try { return valueOf(id); }
        catch (IllegalArgumentException e) { return null; }
    }
}
