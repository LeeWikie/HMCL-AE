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

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
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

    private static final double MAX_WIDTH = 470;

    private static final Parser PARSER = Parser.builder()
            .extensions(Arrays.asList(TablesExtension.create(), StrikethroughExtension.create()))
            .build();

    private MarkdownMessageView() {
        getStyleClass().add("ai-markdown-view");
        setMaxWidth(MAX_WIDTH);
        setFillWidth(true);
        setSpacing(6);
    }

    /// Builds a Markdown view when the text actually contains Markdown; returns null for
    /// plain prose so the caller can keep a lightweight Label.
    @Nullable
    public static MarkdownMessageView create(String text) {
        if (!MarkdownRenderer.containsMarkdownSyntax(text)) return null;
        MarkdownMessageView view = new MarkdownMessageView();
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
            double size = switch (heading.getLevel()) {
                case 1 -> 18;
                case 2 -> 16;
                case 3 -> 15;
                default -> 14;
            };
            for (javafx.scene.Node child : flow.getChildren()) {
                if (child instanceof javafx.scene.text.Text t) {
                    t.setFont(Font.font(null, FontWeight.BOLD, size));
                }
            }
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
            return codeBlock(code.getLiteral());
        }
        if (node instanceof IndentedCodeBlock code) {
            return codeBlock(code.getLiteral());
        }
        if (node instanceof BlockQuote) {
            VBox inner = new VBox(4);
            inner.setFillWidth(true);
            appendBlocks(inner, node);
            HBox quote = new HBox(inner);
            HBox.setHgrow(inner, Priority.ALWAYS);
            quote.getStyleClass().add("md-blockquote");
            quote.setMaxWidth(MAX_WIDTH);
            return quote;
        }
        if (node instanceof ThematicBreak) {
            Region hr = new Region();
            hr.getStyleClass().add("md-hr");
            hr.setPrefHeight(1);
            hr.setMaxWidth(MAX_WIDTH);
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
        list.setMaxWidth(MAX_WIDTH);
        int index = 1;
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
            row.setMaxWidth(MAX_WIDTH);
            list.getChildren().add(row);
            index++;
        }
        return list;
    }

    private javafx.scene.Node renderTable(TableBlock tableBlock) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("md-table");
        grid.setMaxWidth(MAX_WIDTH);
        int rowIndex = 0;
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
                    HBox cell = new HBox(cellFlow);
                    cell.getStyleClass().add(header ? "md-th" : "md-td");
                    cell.setPadding(new Insets(4, 8, 4, 8));
                    cell.setMaxWidth(Double.MAX_VALUE);
                    grid.add(cell, col, rowIndex);
                    GridPane.setHgrow(cell, Priority.ALWAYS);
                    col++;
                }
                rowIndex++;
            }
        }
        return grid;
    }

    // ---- Inline-level ----

    private void renderInline(TextFlow flow, Node parent, boolean bold, boolean italic, boolean strike) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            appendInline(flow, node, bold, italic, strike);
        }
    }

    private void appendInline(TextFlow flow, Node node, boolean bold, boolean italic, boolean strike) {
        if (node instanceof org.commonmark.node.Text text) {
            String literal = text.getLiteral();
            if (EmojiImages.containsEmoji(literal)) {
                // Emoji present: split into text runs + inline emoji images. Black-and-white by
                // default (local Noto raster, fills glyphs the system font lacks), colour Twemoji
                // when the user enables it.
                flow.getChildren().addAll(EmojiImages.toNodes(literal, 13));
            } else {
                flow.getChildren().add(styledText(literal, bold, italic, false, strike));
            }
        } else if (node instanceof StrongEmphasis) {
            renderInline(flow, node, true, italic, strike);
        } else if (node instanceof Emphasis) {
            renderInline(flow, node, bold, true, strike);
        } else if (node instanceof Strikethrough) {
            renderInline(flow, node, bold, italic, true);
        } else if (node instanceof Code code) {
            flow.getChildren().add(styledText(code.getLiteral(), false, false, true, strike));
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

    private javafx.scene.text.Text styledText(String s, boolean bold, boolean italic, boolean code, boolean strike) {
        javafx.scene.text.Text t = new javafx.scene.text.Text(s);
        FontWeight weight = bold ? FontWeight.BOLD : FontWeight.NORMAL;
        FontPosture posture = italic ? FontPosture.ITALIC : FontPosture.REGULAR;
        if (code) {
            t.setFont(Font.font("Monospaced", weight, posture, 12));
            t.getStyleClass().add("md-code-inline");
        } else {
            t.setFont(Font.font(null, weight, posture, 13));
        }
        if (strike) t.setStrikethrough(true);
        t.getStyleClass().add("md-text");
        return t;
    }

    private TextFlow baseFlow() {
        TextFlow flow = new TextFlow();
        flow.setMaxWidth(MAX_WIDTH);
        flow.setLineSpacing(2);
        return flow;
    }

    private javafx.scene.Node codeBlock(String code) {
        String body = code != null && code.endsWith("\n") ? code.substring(0, code.length() - 1) : code;
        Label label = new Label(body);
        label.setFont(Font.font("Monospaced", 12));
        label.setWrapText(true);
        label.setMaxWidth(MAX_WIDTH);
        label.getStyleClass().add("md-code-block");
        return label;
    }
}
