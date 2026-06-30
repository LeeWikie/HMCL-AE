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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks in the Zhipu response parsing (the {@code search_result:[{title,link,content}]} shape) and
/// tolerance of empty / null / malformed payloads.
public final class ZhipuSearchClientTest {

    @Test
    void parsesSearchResultArray() {
        String json = "{\"id\":\"x\",\"created\":123,\"request_id\":\"r\",\"search_result\":["
                + "{\"title\":\"T1\",\"link\":\"https://a.com\",\"content\":\"c1\",\"media\":\"搜狐\",\"refer\":\"ref_1\"},"
                + "{\"title\":\"T2\",\"link\":\"https://b.com\",\"content\":\"c2\"}"
                + "]}";
        List<SearchResult> results = ZhipuSearchClient.parseResults(json).results();
        assertEquals(2, results.size());
        assertEquals("T1", results.get(0).title());
        assertEquals("https://a.com", results.get(0).url());
        assertEquals("c1", results.get(0).snippet());
        assertEquals("https://b.com", results.get(1).url());
    }

    @Test
    void emptyNullAndMalformedYieldNoResultsWithoutThrowing() {
        assertTrue(ZhipuSearchClient.parseResults("").results().isEmpty());
        assertTrue(ZhipuSearchClient.parseResults("{}").results().isEmpty());
        assertTrue(ZhipuSearchClient.parseResults("{\"search_result\":[]}").results().isEmpty());
        assertEquals("zhipu", ZhipuSearchClient.parseResults("{}").provider());
    }
}
