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
package org.jackhuang.hmcl.ai.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Locks in the containsMarkdownSyntax() heuristics: (1) the table-delimiter-row heuristic must
/// recognize colon-aligned GFM delimiter rows (`|:--|`, `|:-:|`), not just plain `|---|`; (2) the
/// header/list/blockquote regexes must detect their construct at the start of ANY line, not only
/// at the absolute start of the whole message; (3) bare URLs / www links must count as "worth
/// rendering as Markdown" so the autolink extension can linkify them (the linkification itself is
/// wired in MarkdownMessageView's JavaFX parser and verified there / visually).
public final class MarkdownRendererTest {

    @Test
    public void plainProseIsNotMarkdown() {
        assertFalse(MarkdownRenderer.containsMarkdownSyntax("这只是一段普通的文字，没有任何格式。"));
        assertFalse(MarkdownRenderer.containsMarkdownSyntax("hello there, nice to meet you"));
    }

    @Test
    public void nullAndEmptyAreNotMarkdown() {
        assertFalse(MarkdownRenderer.containsMarkdownSyntax(null));
        assertFalse(MarkdownRenderer.containsMarkdownSyntax(""));
    }

    @Test
    public void plainDashDelimiterTableIsDetected() {
        assertTrue(MarkdownRenderer.containsMarkdownSyntax("|A|B|\n|---|---|\n|1|2|\n"));
    }

    @Test
    public void leftAlignedColonDelimiterTableIsDetected() {
        assertTrue(MarkdownRenderer.containsMarkdownSyntax("|A|B|\n|:--|:--|\n|1|2|\n"),
                "a colon-prefixed (left-aligned) delimiter row is valid GFM and must be detected");
    }

    @Test
    public void centerAlignedColonDelimiterTableIsDetected() {
        assertTrue(MarkdownRenderer.containsMarkdownSyntax("|A|B|\n|:-:|:-:|\n|1|2|\n"),
                "a center-aligned delimiter row is valid GFM and must be detected");
    }

    @Test
    public void headingAfterIntroTextIsDetected() {
        assertTrue(MarkdownRenderer.containsMarkdownSyntax("这是结果：\n# 标题\n正文"),
                "a heading following intro text on its own line must still be detected");
    }

    @Test
    public void bulletListAfterIntroTextIsDetected() {
        assertTrue(MarkdownRenderer.containsMarkdownSyntax("这是结果：\n- item1\n- item2\n"),
                "a bullet list following intro prose must still be detected");
    }

    @Test
    public void orderedListAfterIntroTextIsDetected() {
        assertTrue(MarkdownRenderer.containsMarkdownSyntax("说明如下：\n1. 第一步\n2. 第二步\n"));
    }

    @Test
    public void blockquoteAfterIntroTextIsDetected() {
        assertTrue(MarkdownRenderer.containsMarkdownSyntax("引用一下：\n> 这是引用内容\n"));
    }

    @Test
    public void bulletListAtStartIsStillDetected() {
        // Pre-existing (already-working) case: must not regress.
        assertTrue(MarkdownRenderer.containsMarkdownSyntax("- item1\n- item2\n"));
    }

    @Test
    public void plainProseWithJobProgressMarkerIsDetected() {
        // Otherwise-plain prose carrying only the inline live-badge marker (no other Markdown
        // construct) must still route through the AST renderer, or the marker would render as
        // inert literal text instead of a live JobProgressBadge.
        assertTrue(MarkdownRenderer.containsMarkdownSyntax(
                "已安排后台下载mod，已安装 {{job_progress:1}}，还有什么别的要求吗？"));
        assertTrue(MarkdownRenderer.containsMarkdownSyntax("{{job_progress:2,3,4,5}}"));
    }

    @Test
    public void bareUrlCountsAsMarkdown() {
        // Bare URLs are linkified by the autolink extension, so they must route through the rich
        // renderer instead of a flat Label.
        assertTrue(MarkdownRenderer.containsMarkdownSyntax("see https://example.com/download for details"));
        assertTrue(MarkdownRenderer.containsMarkdownSyntax("http://localhost:8080"));
        assertTrue(MarkdownRenderer.containsMarkdownSyntax("visit www.minecraft.net today"));
    }
}
