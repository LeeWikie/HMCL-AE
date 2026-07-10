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

import org.jetbrains.annotations.NotNullByDefault;

/// Markdown-syntax detection heuristic used to decide whether an AI chat message should be
/// rendered through {@code MarkdownMessageView}'s native JavaFX AST walker (see the `ui/ai`
/// module) or shown as a plain-text {@code Label}.
///
/// This class previously also rendered Markdown to an HTML string (via commonmark-java) for
/// display in a JavaFX WebView; that approach was replaced by {@code MarkdownMessageView}
/// walking the commonmark AST directly into native JavaFX nodes (no WebView dependency), so the
/// HTML-rendering methods were removed as dead code — only the syntax-detection heuristic below
/// is still used.
@NotNullByDefault
public final class MarkdownRenderer {

    private MarkdownRenderer() {}

    /// Matches a GFM table delimiter row (`|---|---|`, `|:--|:--|`, `|:-:|:-:|`, ...) regardless of
    /// colon-alignment placement/spacing — a plain `.contains("|---")`/`.contains("| ---")` check
    /// misses any column whose delimiter starts with a colon (left/center alignment), which is
    /// common in real GFM tables.
    private static final java.util.regex.Pattern TABLE_DELIMITER_ROW =
            java.util.regex.Pattern.compile(
                    "(?m)^[ \\t]*\\|?[ \\t]*:?-{1,}:?[ \\t]*(\\|[ \\t]*:?-{1,}:?[ \\t]*)+\\|?[ \\t]*$");

    /// Quick heuristic to determine whether text contains Markdown formatting
    /// that would benefit from HTML rendering. Returns {@code false} for plain
    /// prose so callers can fall back to a lightweight JavaFX Label.
    public static boolean containsMarkdownSyntax(String text) {
        if (text == null || text.isEmpty()) return false;
        // Inline live job-progress badge marker (see MarkdownMessageView/JobProgressBadge in the
        // HMCL module). Plain prose carrying only this marker and no other Markdown construct —
        // e.g. "已安装 {{job_progress:1}}，还有什么别的要求吗？" — would otherwise be judged
        // "not Markdown" and fall back to a bare Label, and the marker would render as inert
        // literal text instead of a live badge. `{{job_progress:` is deliberately distinctive
        // enough that a plain substring check can't collide with real prose/Markdown.
        if (text.contains("{{job_progress:")) return true;
        // Code blocks or inline code
        if (text.contains("```") || text.contains("`")) return true;
        // Headers — (?m) so `^` matches the start of ANY line, not just the whole string; without
        // it a heading/list/blockquote that follows any intro prose (the common LLM-output shape,
        // e.g. "这是结果：\n- item1") was never detected.
        if (text.matches("(?sm).*^#{1,6}\\s.*")) return true;
        // Tables
        if (TABLE_DELIMITER_ROW.matcher(text).find()) return true;
        // Bold / italic
        if (text.contains("**") || text.contains("__") || text.contains("*") || text.contains("_")) return true;
        // Lists
        if (text.matches("(?sm).*^[\\-*+]\\s.*")) return true;
        if (text.matches("(?sm).*^\\d+\\.\\s.*")) return true;
        // Links or images
        if (text.contains("](") || text.contains("![")) return true;
        // Blockquotes
        if (text.matches("(?sm).*^>\\s.*")) return true;
        return false;
    }
}
