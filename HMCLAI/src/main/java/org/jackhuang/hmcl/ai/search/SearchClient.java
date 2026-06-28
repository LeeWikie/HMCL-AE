package org.jackhuang.hmcl.ai.search;

import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
public interface SearchClient {
    SearchResponse search(String query, int maxResults) throws Exception;
}
