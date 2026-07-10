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

import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

/// Multi-level match fallback chain for [`EditTool`], modeled on opencode's Replacer chain
/// (2-3 pragmatic levels out of its 9, plus the anti-over-match circuit breaker):
///
///   1. **exact** — plain substring match (today's behavior; non-overlapping occurrences).
///   2. **line-trimmed** — every line of `old_string` and of the candidate window is compared
///      after `strip()`, absorbing leading/trailing whitespace, indentation drift and
///      CRLF-vs-LF differences (the most common near-miss causes).
///   3. **whole-trimmed** — `old_string.strip()` matched exactly, absorbing stray leading /
///      trailing blank lines or spaces the model added around an otherwise exact snippet.
///   4. **whitespace-normalized** — like line-trimmed, but interior whitespace runs are also
///      collapsed to a single space before comparing.
///
/// The first level that yields at least one match wins; later levels are never consulted, so
/// an exact match can never be shadowed by a fuzzy one. Fuzzy levels (2-4) replace the whole
/// matched span as it appears in the file, so the replacement text's own whitespace wins —
/// same trade-off opencode makes.
///
/// **Circuit breaker**: any fuzzy match whose actual span is disproportionately larger than
/// `old_string` (chars beyond `max(len+500, len*4)` or lines beyond `max(lines+3, lines*2)`)
/// is refused instead of applied, because it almost certainly grabbed unintended text.
@NotNullByDefault
final class EditReplacers {

    private EditReplacers() {
    }

    /// A half-open `[start, end)` character span in the original content.
    record Span(int start, int end) {
        int length() {
            return end - start;
        }
    }

    /// Outcome of running the chain: the matching spans (empty = no level matched), the name
    /// of the level that produced them (`"exact"`, `"line-trimmed"`, ...), and — when the
    /// circuit breaker fired — the offending span (with `spans` empty).
    record Result(List<Span> spans, String strategy, boolean disproportionate) {
        static final Result NO_MATCH = new Result(List.of(), "none", false);

        boolean isExact() {
            return "exact".equals(strategy);
        }
    }

    /// Runs the fallback chain and returns the first level's matches (see class doc).
    static Result locate(String content, String oldString) {
        List<Span> exact = exactSpans(content, oldString);
        if (!exact.isEmpty()) {
            return new Result(exact, "exact", false);
        }

        Result lineTrimmed = guarded(content, windowSpans(content, oldString, EditReplacers::stripLine),
                "line-trimmed", oldString);
        if (lineTrimmed != null) {
            return lineTrimmed;
        }

        String trimmed = oldString.strip();
        if (!trimmed.isEmpty() && !trimmed.equals(oldString)) {
            List<Span> wholeTrimmed = exactSpans(content, trimmed);
            if (!wholeTrimmed.isEmpty()) {
                // Exact search of a strictly smaller needle can never be disproportionate.
                return new Result(wholeTrimmed, "whole-trimmed", false);
            }
        }

        Result normalized = guarded(content, windowSpans(content, oldString, EditReplacers::normalizeWhitespace),
                "whitespace-normalized", oldString);
        if (normalized != null) {
            return normalized;
        }

        return Result.NO_MATCH;
    }

    /// Applies the circuit breaker to a fuzzy level's spans; returns `null` when the level
    /// found nothing (so the chain continues).
    private static Result guarded(String content, List<Span> spans, String strategy, String oldString) {
        if (spans.isEmpty()) {
            return null;
        }
        for (Span span : spans) {
            if (isDisproportionate(oldString, content.substring(span.start(), span.end()))) {
                return new Result(List.of(span), strategy, true);
            }
        }
        return new Result(spans, strategy, false);
    }

    /// Non-overlapping exact occurrences of `needle` in `haystack`.
    private static List<Span> exactSpans(String haystack, String needle) {
        List<Span> spans = new ArrayList<>();
        if (needle.isEmpty()) {
            return spans;
        }
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            spans.add(new Span(i, i + needle.length()));
        }
        return spans;
    }

    /// Sliding line-window matcher: splits both sides into lines, canonicalizes each line with
    /// `canon`, and matches consecutive line windows. A match spans from the first matched
    /// line's start to the last matched line's end (its terminator excluded). Windows do not
    /// overlap (the cursor jumps past a match), mirroring exact-substring counting semantics.
    private static List<Span> windowSpans(String content, String oldString,
                                          java.util.function.UnaryOperator<String> canon) {
        List<String> needleLines = new ArrayList<>(oldString.lines().map(canon).toList());
        // A trailing newline in old_string produces no extra element with String#lines(), but
        // the model often includes one leading/trailing BLANK line around the real snippet —
        // drop those so they don't have to match a physical blank line in the file.
        while (!needleLines.isEmpty() && needleLines.get(0).isEmpty()) {
            needleLines.remove(0);
        }
        while (!needleLines.isEmpty() && needleLines.get(needleLines.size() - 1).isEmpty()) {
            needleLines.remove(needleLines.size() - 1);
        }
        if (needleLines.isEmpty()) {
            return List.of();
        }

        List<int[]> lineOffsets = lineOffsets(content); // [start, end) excluding the terminator
        int lineCount = lineOffsets.size();
        String[] canonLines = new String[lineCount];
        for (int i = 0; i < lineCount; i++) {
            canonLines[i] = canon.apply(content.substring(lineOffsets.get(i)[0], lineOffsets.get(i)[1]));
        }

        List<Span> spans = new ArrayList<>();
        int n = needleLines.size();
        for (int i = 0; i + n <= lineCount; ) {
            boolean match = true;
            for (int j = 0; j < n; j++) {
                if (!canonLines[i + j].equals(needleLines.get(j))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                spans.add(new Span(lineOffsets.get(i)[0], lineOffsets.get(i + n - 1)[1]));
                i += n;
            } else {
                i++;
            }
        }
        return spans;
    }

    /// `[start, end)` offsets of every line in `content`, terminator (`\n`, `\r`, `\r\n`)
    /// excluded from the span but skipped by the scan.
    private static List<int[]> lineOffsets(String content) {
        List<int[]> offsets = new ArrayList<>();
        int start = 0;
        int i = 0;
        int len = content.length();
        while (i < len) {
            char c = content.charAt(i);
            if (c == '\n' || c == '\r') {
                offsets.add(new int[]{start, i});
                if (c == '\r' && i + 1 < len && content.charAt(i + 1) == '\n') {
                    i++;
                }
                i++;
                start = i;
            } else {
                i++;
            }
        }
        offsets.add(new int[]{start, len}); // last line (possibly empty when content ends with a terminator)
        return offsets;
    }

    private static String stripLine(String line) {
        return line.strip();
    }

    /// Collapses every whitespace run to a single space and strips the ends.
    private static String normalizeWhitespace(String line) {
        return line.strip().replaceAll("\\s+", " ");
    }

    /// Anti-over-match circuit breaker (opencode `isDisproportionateMatch`): the matched text
    /// is suspiciously larger than `old_string` when its character count exceeds
    /// `max(old+500, old*4)` or its line count exceeds `max(oldLines+3, oldLines*2)`.
    /// Package-private for direct unit testing.
    static boolean isDisproportionate(String oldString, String matchedText) {
        int oldLen = oldString.length();
        if (matchedText.length() > Math.max(oldLen + 500, oldLen * 4L)) {
            return true;
        }
        long oldLines = Math.max(1, oldString.lines().count());
        long matchedLines = Math.max(1, matchedText.lines().count());
        return matchedLines > Math.max(oldLines + 3, oldLines * 2);
    }
}
