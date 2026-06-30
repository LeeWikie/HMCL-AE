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

/// Locks in the Bocha response parsing (the {code,data:{webPages:{value:[...]}}} envelope), incl.
/// the summary-preferred-over-snippet rule and tolerance of malformed / flat / empty payloads.
public final class BochaSearchClientTest {

    @Test
    void parsesDocumentedEnvelopeAndPrefersSummary() {
        String json = "{"
                + "\"code\":200,\"log_id\":\"x\",\"msg\":null,"
                + "\"data\":{\"_type\":\"SearchResponse\",\"queryContext\":{\"originalQuery\":\"minecraft\"},"
                + "\"webPages\":{\"value\":["
                + "{\"name\":\"Title A\",\"url\":\"https://a.com\",\"snippet\":\"snip A\",\"summary\":\"summary A\"},"
                + "{\"name\":\"Title B\",\"url\":\"https://b.com\",\"snippet\":\"snip B\"}"
                + "]}}}";
        List<SearchResult> results = BochaSearchClient.parseResults(json).results();
        assertEquals(2, results.size());
        assertEquals("Title A", results.get(0).title());
        assertEquals("https://a.com", results.get(0).url());
        assertEquals("summary A", results.get(0).snippet()); // summary preferred over snippet
        assertEquals("Title B", results.get(1).title());
        assertEquals("snip B", results.get(1).snippet()); // falls back to snippet when no summary
    }

    @Test
    void toleratesFlatShapeWithoutDataEnvelope() {
        String json = "{\"webPages\":{\"value\":[{\"name\":\"T\",\"url\":\"https://t.com\",\"summary\":\"s\"}]}}";
        List<SearchResult> results = BochaSearchClient.parseResults(json).results();
        assertEquals(1, results.size());
        assertEquals("https://t.com", results.get(0).url());
    }

    @Test
    void emptyNullAndMalformedYieldNoResultsWithoutThrowing() {
        assertTrue(BochaSearchClient.parseResults("").results().isEmpty());
        assertTrue(BochaSearchClient.parseResults("{}").results().isEmpty());
        assertTrue(BochaSearchClient.parseResults("{\"data\":{}}").results().isEmpty());
        assertTrue(BochaSearchClient.parseResults("{\"data\":{\"webPages\":{}}}").results().isEmpty());
        assertEquals("bocha", BochaSearchClient.parseResults("{}").provider());
    }
}
