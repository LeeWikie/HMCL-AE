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
package org.jackhuang.hmcl.ui.ai;

import com.jfoenix.controls.JFXButton;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.util.Duration;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.jackhuang.hmcl.ai.markdown.MarkdownRenderer;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/// Renders Markdown as native JavaFX nodes by walking the commonmark AST.
///
/// Paragraphs/headings become {@link TextFlow}s, tables become {@link GridPane}s,
/// lists become indented rows, code blocks become monospace cards, etc. This avoids
/// a WebView (which HMCL does not bundle) while still supporting GFM tables,
/// strikethrough and links via the commonmark library. Used only by the AI chat.
public final class MarkdownMessageView extends VBox {

    // Max content width, supplied by the caller (AIMainPage passes AI_BUBBLE_MAX_WIDTH - 10) so
    // the bubble width has a single source of truth instead of a second hard-coded magic number.
    // setMaxWidth is only a ceiling: the parent VBox's fillWidth layout already caps children to
    // the true available content width, so the exact number here isn't load-bearing.
    private final double maxWidth;

    private static final Parser PARSER = Parser.builder()
            .extensions(Arrays.asList(TablesExtension.create(), StrikethroughExtension.create()))
            .build();

    /// Matches the {@code {{job_progress:<id>[,<id>...]}}} live-badge syntax inside inline text
    /// (see {@link JobProgressBadge}). Deliberately permissive on the captured ids — validation
    /// (does the id/list actually exist?) happens later, in {@link JobProgressBadge}, so a
    /// hallucinated id renders a graceful fallback badge rather than being rejected here and
    /// falling through to literal, confusing {@code {{...}}} text.
    private static final java.util.regex.Pattern JOB_PROGRESS_PATTERN =
            java.util.regex.Pattern.compile("\\{\\{job_progress:([^}]*)}}");

    private MarkdownMessageView(double maxWidth) {
        this.maxWidth = maxWidth;
        getStyleClass().add("ai-markdown-view");
        setMaxWidth(maxWidth);
        setFillWidth(true);
        setSpacing(6);
    }

    /// Builds a Markdown view when the text actually contains Markdown; returns null for
    /// plain prose so the caller can keep a lightweight Label.
    ///
    /// @param maxWidth width ceiling for every rendered block (callers derive it from
    ///                 {@code AIMainPage.AI_BUBBLE_MAX_WIDTH} minus bubble padding slack).
    @Nullable
    public static MarkdownMessageView create(String text, double maxWidth) {
        if (!MarkdownRenderer.containsMarkdownSyntax(text)) return null;
        MarkdownMessageView view = new MarkdownMessageView(maxWidth);
        Node document = PARSER.parse(text != null ? text : "");
        view.appendBlocks(view, document);
        return view.getChildren().isEmpty() ? null : view;
    }

    // ---- Block-level ----

    private void appendBlocks(Pane container, Node parent) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            javafx.scene.Node rendered = renderBlock(node);
            if (rendered != null) container.getChildren().add(rendered);
        }
    }

    @Nullable
    private javafx.scene.Node renderBlock(Node node) {
        if (node instanceof Heading heading) {
            TextFlow flow = baseFlow();
            renderInline(flow, heading, false, false, false);
            // Size/weight come from CSS (md-h1..md-h4 em rules in root.css) so the reader's
            // chat font-size setting scales headings too; levels 5/6 share the md-h4 style.
            // Nested emphasis keeps its italic via the md-em class styledText() attaches.
            flow.getStyleClass().add("md-h" + Math.min(heading.getLevel(), 4));
            return flow;
        }
        if (node instanceof Paragraph paragraph) {
            TextFlow flow = baseFlow();
            renderInline(flow, paragraph, false, false, false);
            return flow;
        }
        if (node instanceof BulletList list) {
            return renderList(list, false);
        }
        if (node instanceof OrderedList list) {
            return renderList(list, true);
        }
        if (node instanceof FencedCodeBlock code) {
            return codeBlock(code.getLiteral(), code.getInfo());
        }
        if (node instanceof IndentedCodeBlock code) {
            return codeBlock(code.getLiteral(), null);
        }
        if (node instanceof BlockQuote) {
            VBox inner = new VBox(4);
            inner.setFillWidth(true);
            appendBlocks(inner, node);
            HBox quote = new HBox(inner);
            HBox.setHgrow(inner, Priority.ALWAYS);
            quote.getStyleClass().add("md-blockquote");
            quote.setMaxWidth(maxWidth);
            return quote;
        }
        if (node instanceof ThematicBreak) {
            Region hr = new Region();
            hr.getStyleClass().add("md-hr");
            hr.setPrefHeight(1);
            hr.setMaxWidth(maxWidth);
            return hr;
        }
        if (node instanceof TableBlock table) {
            return renderTable(table);
        }
        // Unknown block with children — render them in place.
        if (node.getFirstChild() != null) {
            VBox box = new VBox(4);
            box.setFillWidth(true);
            appendBlocks(box, node);
            return box;
        }
        return null;
    }

    private javafx.scene.Node renderList(Node listNode, boolean ordered) {
        VBox list = new VBox(2);
        list.setFillWidth(true);
        list.setMaxWidth(maxWidth);
        int index = ordered && listNode instanceof OrderedList orderedList ? orderedList.getStartNumber() : 1;
        for (Node item = listNode.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem)) continue;
            Label marker = new Label(ordered ? (index + ".") : "•");
            marker.getStyleClass().add("md-list-marker");
            marker.setMinWidth(Region.USE_PREF_SIZE);
            VBox content = new VBox(2);
            content.setFillWidth(true);
            appendBlocks(content, item);
            HBox.setHgrow(content, Priority.ALWAYS);
            HBox row = new HBox(6, marker, content);
            row.setAlignment(Pos.TOP_LEFT);
            row.setMaxWidth(maxWidth);
            list.getChildren().add(row);
            index++;
        }
        return list;
    }

    // Flat per-column floor: enough for several CJK glyphs or ~8-10 Latin chars at this
    // view's ~13px cell font, so GridPane's auto-shrink can never squeeze a column below
    // one word's width and force TextFlow into mid-word wrapping.
    private static final double MIN_COLUMN_WIDTH = 60;

    // Cap so a many-row table renders as a bounded, independently vertically-scrollable
    // block instead of one very tall unbroken slab inside the non-virtualized messageList
    // VBox. Row height is *estimated* (cell padding 4+4 + this view's ~13px cell font/line
    // spacing + a hairline border) rather than measured via a real layout pass, since the
    // grid is not yet attached to a Scene when this decision is made and an accurate
    // measurement would require knowing the resolved column widths first.
    private static final double ESTIMATED_ROW_HEIGHT = 30;
    private static final double MAX_TABLE_HEIGHT = 260;

    private javafx.scene.Node renderTable(TableBlock tableBlock) {
        TableGrid grid = new TableGrid();
        grid.getStyleClass().add("md-table");
        grid.setMaxWidth(maxWidth);
        // Screen-reader wiring: a table rendered as a bare GridPane otherwise carries zero
        // row/column semantics — a screen reader just sees an unstructured pile of Text
        // nodes. TABLE_VIEW plus the ROW_COUNT/COLUMN_COUNT answers wired into TableGrid
        // below are the closest match JavaFX's accessibility API offers a hand-built grid
        // (see Node#queryAccessibleAttribute / AccessibleRole#TABLE_VIEW).
        grid.setAccessibleRole(AccessibleRole.TABLE_VIEW);
        int rowIndex = 0;
        int maxCol = 0;
        // Column header text captured while walking TableHead, so each TableBody cell's
        // AccessibleText below can announce "<header>: <value>" instead of a bare value.
        // TableHead always precedes TableBody in the GFM table AST, so this list is fully
        // populated before any TableBody row needs to read from it.
        java.util.List<String> headerTexts = new java.util.ArrayList<>();
        // Sections: TableHead then TableBody (each holds TableRows).
        for (Node section = tableBlock.getFirstChild(); section != null; section = section.getNext()) {
            boolean header = "TableHead".equals(section.getClass().getSimpleName());
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                if (!(row instanceof TableRow)) continue;
                int col = 0;
                for (Node cellNode = row.getFirstChild(); cellNode != null; cellNode = cellNode.getNext()) {
                    if (!(cellNode instanceof TableCell)) continue;
                    TextFlow cellFlow = new TextFlow();
                    renderInline(cellFlow, cellNode, header, false, false);
                    TableCell.Alignment alignment = ((TableCell) cellNode).getAlignment();
                    if (alignment == TableCell.Alignment.CENTER) {
                        cellFlow.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
                    } else if (alignment == TableCell.Alignment.RIGHT) {
                        cellFlow.setTextAlignment(javafx.scene.text.TextAlignment.RIGHT);
                    }
                    // Read from the AST rather than the rendered TextFlow so the accessible
                    // text is correct even when EmojiImages swapped a glyph for an inline
                    // image (which carries no text of its own).
                    String plainText = plainInlineText(cellNode);
                    if (header && col == headerTexts.size()) {
                        headerTexts.add(plainText);
                    }
                    TableCellBox cell = new TableCellBox(cellFlow, rowIndex, col);
                    cell.getStyleClass().add(header ? "md-th" : "md-td");
                    cell.setPadding(new Insets(4, 8, 4, 8));
                    cell.setMaxWidth(Double.MAX_VALUE);
                    if (alignment == TableCell.Alignment.CENTER) {
                        cell.setAlignment(Pos.CENTER);
                    } else if (alignment == TableCell.Alignment.RIGHT) {
                        cell.setAlignment(Pos.CENTER_RIGHT);
                    }
                    cell.setAccessibleRole(AccessibleRole.TABLE_CELL);
                    if (header) {
                        cell.setAccessibleRoleDescription("表头");
                        cell.setAccessibleText(plainText);
                    } else {
                        String headerText = col < headerTexts.size() ? headerTexts.get(col) : null;
                        cell.setAccessibleText(headerText != null && !headerText.isBlank()
                                ? headerText + ": " + plainText
                                : plainText);
                    }
                    grid.add(cell, col, rowIndex);
                    GridPane.setHgrow(cell, Priority.ALWAYS);
                    col++;
                }
                maxCol = Math.max(maxCol, col);
                rowIndex++;
            }
        }
        grid.setRowAndColumnCount(rowIndex, maxCol);
        // Without an explicit per-column minimum, GridPane's auto-sizing can shrink a
        // short-content column narrower than a single glyph once the row's total preferred
        // width exceeds maxWidth, forcing TextFlow to wrap mid-word/mid-character.
        for (int i = 0; i < maxCol; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setMinWidth(MIN_COLUMN_WIDTH);
            cc.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(cc);
        }

        // Per-column minimums can legitimately force the grid wider than maxWidth (JavaFX
        // resolves a min>max conflict by honoring min). Scope horizontal scrolling to just
        // this table's row so it doesn't overflow the bubble or scroll the whole conversation.
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(false);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // A many-row table is capped to MAX_TABLE_HEIGHT and only then gains its own
        // vertical scrollbar — a table that already fits keeps NEVER so it never shows an
        // unnecessary scrollbar for a handful of rows.
        boolean overflowsHeightCap = rowIndex * ESTIMATED_ROW_HEIGHT > MAX_TABLE_HEIGHT;
        scroll.setMaxHeight(MAX_TABLE_HEIGHT);
        scroll.setVbarPolicy(overflowsHeightCap
                ? ScrollPane.ScrollBarPolicy.AS_NEEDED
                : ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMaxWidth(maxWidth);
        return scroll;
    }

    /// A {@link GridPane} that answers the ROW_COUNT/COLUMN_COUNT accessibility queries a
    /// screen reader issues against an {@link AccessibleRole#TABLE_VIEW} — plain GridPane has
    /// no notion of "how many rows/columns" since it is just a generic layout container, so
    /// {@link javafx.scene.Node#queryAccessibleAttribute} would otherwise fall through to the default
    /// (non-table) answers for those two attributes.
    private static final class TableGrid extends GridPane {
        private int rowCount;
        private int columnCount;

        void setRowAndColumnCount(int rowCount, int columnCount) {
            this.rowCount = rowCount;
            this.columnCount = columnCount;
        }

        @Override
        public Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
            switch (attribute) {
                case ROW_COUNT:
                    return rowCount;
                case COLUMN_COUNT:
                    return columnCount;
                default:
                    return super.queryAccessibleAttribute(attribute, parameters);
            }
        }
    }

    /// An {@link HBox} that answers a cell's own ROW_INDEX/COLUMN_INDEX accessibility
    /// queries — the coordinates a screen reader needs to announce e.g. "row 2, column 3"
    /// while it is focused on an {@link AccessibleRole#TABLE_CELL}.
    private static final class TableCellBox extends HBox {
        private final int rowIndex;
        private final int columnIndex;

        TableCellBox(javafx.scene.Node content, int rowIndex, int columnIndex) {
            super(content);
            this.rowIndex = rowIndex;
            this.columnIndex = columnIndex;
        }

        @Override
        public Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
            switch (attribute) {
                case ROW_INDEX:
                    return rowIndex;
                case COLUMN_INDEX:
                    return columnIndex;
                default:
                    return super.queryAccessibleAttribute(attribute, parameters);
            }
        }
    }

    /// Concatenates the literal text of an inline AST subtree — ignoring emphasis/strike/link
    /// formatting nodes but keeping their literal text — for use as a cell's accessible text.
    /// Mirrors {@link #appendInline}'s traversal but collects plain text instead of building
    /// styled {@link javafx.scene.text.Text} runs.
    private static String plainInlineText(Node node) {
        StringBuilder sb = new StringBuilder();
        appendPlainInlineText(node, sb);
        return sb.toString();
    }

    private static void appendPlainInlineText(Node parent, StringBuilder sb) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof org.commonmark.node.Text text) {
                sb.append(text.getLiteral());
            } else if (node instanceof Code code) {
                sb.append(code.getLiteral());
            } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
                sb.append(' ');
            } else {
                appendPlainInlineText(node, sb);
            }
        }
    }

    // ---- Inline-level ----

    private void renderInline(TextFlow flow, Node parent, boolean bold, boolean italic, boolean strike) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            appendInline(flow, node, bold, italic, strike);
        }
    }

    private void appendInline(TextFlow flow, Node node, boolean bold, boolean italic, boolean strike) {
        if (node instanceof org.commonmark.node.Text text) {
            appendTextWithJobProgress(flow, text.getLiteral(), bold, italic, strike);
        } else if (node instanceof StrongEmphasis) {
            renderInline(flow, node, true, italic, strike);
        } else if (node instanceof Emphasis) {
            renderInline(flow, node, bold, true, strike);
        } else if (node instanceof Strikethrough) {
            renderInline(flow, node, bold, italic, true);
        } else if (node instanceof Code code) {
            flow.getChildren().add(styledText(code.getLiteral(), bold, italic, true, strike));
        } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            flow.getChildren().add(new javafx.scene.text.Text("\n"));
        } else if (node instanceof Link link) {
            String dest = link.getDestination();
            int before = flow.getChildren().size();
            renderInline(flow, node, bold, italic, strike);
            for (int i = before; i < flow.getChildren().size(); i++) {
                if (flow.getChildren().get(i) instanceof javafx.scene.text.Text t) {
                    t.getStyleClass().add("md-link");
                    t.setUnderline(true);
                    if (dest != null && !dest.isEmpty()) {
                        t.setCursor(javafx.scene.Cursor.HAND);
                        t.setOnMouseClicked(e -> FXUtils.openLink(dest));
                    }
                }
            }
        } else if (node instanceof Image) {
            // No inline image rendering; show the alt text instead.
            renderInline(flow, node, bold, italic, strike);
        } else if (node.getFirstChild() != null) {
            renderInline(flow, node, bold, italic, strike);
        }
    }

    /// Scans a raw text run for {@code {{job_progress:...}}} markers and splices a live
    /// {@link JobProgressBadge} node in place of each match, rendering the surrounding text
    /// normally (including colour-emoji substitution) around them. The common case — no marker
    /// present — falls straight through to the original single-run rendering.
    private void appendTextWithJobProgress(TextFlow flow, String literal, boolean bold, boolean italic,
                                            boolean strike) {
        java.util.regex.Matcher m = JOB_PROGRESS_PATTERN.matcher(literal);
        int last = 0;
        boolean matched = false;
        while (m.find()) {
            matched = true;
            if (m.start() > last) {
                appendPlainRun(flow, literal.substring(last, m.start()), bold, italic, strike);
            }
            flow.getChildren().add(JobProgressBadge.create(m.group(1)));
            last = m.end();
        }
        if (!matched) {
            appendPlainRun(flow, literal, bold, italic, strike);
        } else if (last < literal.length()) {
            appendPlainRun(flow, literal.substring(last), bold, italic, strike);
        }
    }

    /// Renders a plain (marker-free) text run, applying colour-emoji substitution when enabled.
    private void appendPlainRun(TextFlow flow, String literal, boolean bold, boolean italic, boolean strike) {
        if (literal.isEmpty()) return;
        if (EmojiImages.isEnabled() && EmojiImages.containsEmoji(literal)) {
            // Colour-emoji mode: split into text runs + inline emoji images.
            flow.getChildren().addAll(EmojiImages.toNodes(literal, 13));
        } else {
            flow.getChildren().add(styledText(literal, bold, italic, false, strike));
        }
    }

    private javafx.scene.text.Text styledText(String s, boolean bold, boolean italic, boolean code, boolean strike) {
        javafx.scene.text.Text t = new javafx.scene.text.Text(s);
        // No setFont here: family/size/weight/posture all come from CSS classes so the chat
        // font-size setting (.ai-chat-font-* on the message list) cascades into Markdown text.
        if (bold) t.getStyleClass().add("md-bold");
        if (italic) t.getStyleClass().add("md-em");
        if (code) t.getStyleClass().add("md-code-inline");
        if (strike) t.setStrikethrough(true);
        return t;
    }

    private TextFlow baseFlow() {
        TextFlow flow = new TextFlow();
        flow.setMaxWidth(maxWidth);
        flow.setLineSpacing(2);
        return flow;
    }

    /// A common-across-languages keyword set for lightweight highlighting. Coloring a word that is a
    /// keyword in one language but an identifier in another is only cosmetic, so a superset is fine.
    private static final java.util.Set<String> CODE_KEYWORDS = java.util.Set.of(
            "if", "else", "for", "while", "do", "return", "function", "def", "class", "import",
            "from", "public", "private", "protected", "static", "final", "void", "int", "long",
            "float", "double", "boolean", "bool", "char", "true", "false", "null", "none", "new",
            "const", "let", "var", "try", "catch", "finally", "throw", "throws", "this", "super",
            "extends", "implements", "package", "interface", "enum", "switch", "case", "break",
            "continue", "in", "of", "not", "and", "or", "is", "lambda", "yield", "with", "as",
            "async", "await", "export", "default", "struct", "fn", "match", "impl");

    /// One pass over code: block comment | line comment | string | number | identifier.
    private static final java.util.regex.Pattern CODE_PATTERN = java.util.regex.Pattern.compile(
            "(/\\*[\\s\\S]*?\\*/)"                                    // 1 block comment
                    + "|(//[^\\n]*|#[^\\n]*)"                         // 2 line comment
                    + "|(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')" // 3 string
                    + "|(\\b\\d[\\w.]*\\b)"                           // 4 number
                    + "|([A-Za-z_]\\w*)");                            // 5 identifier

    /// Renders a fenced/indented code block as a card with a language header, a copy button, and
    /// lightweight (language-agnostic) syntax highlighting for comments, strings, numbers and a
    /// common keyword set.
    private javafx.scene.Node codeBlock(String code, @Nullable String lang) {
        String body = code == null ? "" : (code.endsWith("\n") ? code.substring(0, code.length() - 1) : code);

        Label langLabel = new Label(lang != null && !lang.isBlank() ? lang.trim() : "代码");
        langLabel.getStyleClass().add("md-code-lang");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        JFXButton copyBtn = new JFXButton("复制");
        copyBtn.getStyleClass().add("md-code-copy");
        copyBtn.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(body);
            Clipboard.getSystemClipboard().setContent(cc);
            copyBtn.setText("已复制");
            PauseTransition revert = new PauseTransition(Duration.seconds(1.5));
            revert.setOnFinished(ev -> copyBtn.setText("复制"));
            revert.play();
        });
        HBox header = new HBox(6, langLabel, spacer, copyBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("md-code-header");
        header.setMaxWidth(maxWidth);

        TextFlow content = new TextFlow();
        content.setMaxWidth(maxWidth);
        content.getStyleClass().add("md-code-content");
        appendHighlighted(content, body);

        VBox box = new VBox(header, content);
        box.getStyleClass().add("md-code-block");
        box.setFillWidth(true);
        box.setMaxWidth(maxWidth);
        return box;
    }

    private void appendHighlighted(TextFlow flow, String body) {
        java.util.regex.Matcher m = CODE_PATTERN.matcher(body);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                flow.getChildren().add(codeRun(body.substring(last, m.start()), null));
            }
            String cls;
            if (m.group(1) != null || m.group(2) != null) cls = "md-code-com";
            else if (m.group(3) != null) cls = "md-code-str";
            else if (m.group(4) != null) cls = "md-code-num";
            else cls = CODE_KEYWORDS.contains(m.group().toLowerCase()) ? "md-code-kw" : null;
            flow.getChildren().add(codeRun(m.group(), cls));
            last = m.end();
        }
        if (last < body.length()) {
            flow.getChildren().add(codeRun(body.substring(last), null));
        }
    }

    private javafx.scene.text.Text codeRun(String s, @Nullable String styleClass) {
        javafx.scene.text.Text t = new javafx.scene.text.Text(s);
        // Monospaced family + 0.92em size come from the .md-code-text CSS rule (no setFont),
        // keeping code blocks in step with the chat font-size setting.
        t.getStyleClass().add("md-code-text");
        if (styleClass != null) t.getStyleClass().add(styleClass);
        return t;
    }
}
