package org.jackhuang.hmcl.ai.search;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;

@NotNullByDefault
public record SearchResponse(List<SearchResult> results, String provider) {}
