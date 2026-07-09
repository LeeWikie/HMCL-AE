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
package org.jackhuang.hmcl.ai.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// The EditTool fallback matching chain: exact → line-trimmed → whole-trimmed →
/// whitespace-normalized, plus the anti-over-match circuit breaker.
public final class EditReplacersTest {

    @Test
    void exactMatchWinsAndReportsExactStrategy() {
        EditReplacers.Result r = EditReplacers.locate("foo bar baz", "bar");
        assertEquals("exact", r.strategy());
        assertTrue(r.isExact());
        assertEquals(1, r.spans().size());
        assertEquals(4, r.spans().get(0).start());
        assertEquals(7, r.spans().get(0).end());
    }

    @Test
    void exactMatchCountsNonOverlappingOccurrences() {
        EditReplacers.Result r = EditReplacers.locate("aaaa", "aa");
        assertEquals("exact", r.strategy());
        assertEquals(2, r.spans().size());
    }

    @Test
    void crlfContentMatchesLfOldStringViaLineTrimmedFallback() {
        String content = "line1\r\nline2\r\nline3\r\n";
        EditReplacers.Result r = EditReplacers.locate(content, "line1\nline2");
        assertEquals("line-trimmed", r.strategy());
        assertFalse(r.disproportionate());
        assertEquals(1, r.spans().size());
        assertEquals("line1\r\nline2", content.substring(r.spans().get(0).start(), r.spans().get(0).end()));
    }

    @Test
    void indentationDriftMatchesViaLineTrimmedFallback() {
        String content = "    foo();\n    bar();\n";
        EditReplacers.Result r = EditReplacers.locate(content, "foo();\nbar();");
        assertEquals("line-trimmed", r.strategy());
        assertEquals("    foo();\n    bar();",
                content.substring(r.spans().get(0).start(), r.spans().get(0).end()));
    }

    @Test
    void strayBlankLinesAroundOldStringMatchViaWholeTrimmedFallback() {
        String content = "say hello now";
        EditReplacers.Result r = EditReplacers.locate(content, "\nhello\n");
        // line-trimmed cannot match "hello" against the single line "say hello now";
        // the whole-trimmed level finds the exact inner snippet.
        assertEquals("whole-trimmed", r.strategy());
        assertEquals("hello", content.substring(r.spans().get(0).start(), r.spans().get(0).end()));
    }

    @Test
    void collapsedInteriorWhitespaceMatchesViaNormalizedFallback() {
        String content = "key   =   value\n";
        EditReplacers.Result r = EditReplacers.locate(content, "key = value");
        assertEquals("whitespace-normalized", r.strategy());
        assertEquals("key   =   value", content.substring(r.spans().get(0).start(), r.spans().get(0).end()));
    }

    @Test
    void noLevelMatchingReturnsNoMatch() {
        EditReplacers.Result r = EditReplacers.locate("alpha beta", "gamma");
        assertTrue(r.spans().isEmpty());
        assertFalse(r.disproportionate());
    }

    @Test
    void hugelyPaddedFuzzyMatchTripsTheCircuitBreaker() {
        String content = "a" + " ".repeat(600) + "b\n";
        EditReplacers.Result r = EditReplacers.locate(content, "a b");
        assertTrue(r.disproportionate(),
                "a whitespace-normalized match 200x the old_string size must be refused");
        assertEquals("whitespace-normalized", r.strategy());
    }

    @Test
    void disproportionatePredicateThresholds() {
        // Character heuristic: matched > max(old+500, old*4).
        assertFalse(EditReplacers.isDisproportionate("abc", "abc"));
        assertFalse(EditReplacers.isDisproportionate("abc", "x".repeat(503)));
        assertTrue(EditReplacers.isDisproportionate("abc", "x".repeat(504)));
        // Line heuristic: matched lines > max(oldLines+3, oldLines*2).
        assertTrue(EditReplacers.isDisproportionate("one line", "1\n2\n3\n4\n5"));
        assertFalse(EditReplacers.isDisproportionate("one line", "1\n2\n3\n4"));
    }
}
