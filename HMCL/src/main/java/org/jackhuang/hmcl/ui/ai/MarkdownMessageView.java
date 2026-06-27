package org.jackhuang.hmcl.ui.ai;

import javafx.scene.Node;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.jackhuang.hmcl.ai.markdown.MarkdownRenderer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// A lightweight TextFlow-based Markdown message view using styled JavaFX Text nodes.
/// Falls back to a plain Label when no Markdown syntax is detected.
@NotNullByDefault
public final class MarkdownMessageView extends TextFlow {

    private static final double MAX_WIDTH = 470;
    private static final int BOLD = 0;
    private static final int ITALIC = 1;
    private static final int CODE = 2;
    private static final int PLAIN = 3;

    private MarkdownMessageView() {
        getStyleClass().add("ai-markdown-view");
        setMaxWidth(MAX_WIDTH);
        setLineSpacing(2);
    }

    /// Creates a Markdown TextFlow if the text contains formatting. Returns null for plain prose.
    @Nullable
    public static MarkdownMessageView create(String text) {
        if (!MarkdownRenderer.containsMarkdownSyntax(text)) return null;
        MarkdownMessageView view = new MarkdownMessageView();
        parseAndRender(view, text);
        return view;
    }

    /// Simple inline Markdown parser: handles **bold**, *italic*, `code`, and plain text.
    /// Paragraphs separated by blank lines become line breaks.
    private static void parseAndRender(MarkdownMessageView flow, String text) {
        String[] paragraphs = text.split("\n\n");
        for (int p = 0; p < paragraphs.length; p++) {
            if (p > 0) {
                flow.getChildren().add(new Text("\n\n"));
            }
            String para = paragraphs[p].trim();
            if (para.isEmpty()) continue;

            // Code block: ```
            if (para.startsWith("```") && para.endsWith("```") && para.length() > 6) {
                String code = para.substring(3, para.length() - 3).trim();
                Text codeNode = new Text(code + "\n");
                codeNode.setFont(Font.font("Monospaced", 12));
                codeNode.setStyle("-fx-fill: -monet-on-surface-variant;");
                flow.getChildren().add(codeNode);
                continue;
            }

            // Inline parsing
            parseInline(flow, para);
        }
    }

    private static void parseInline(MarkdownMessageView flow, String line) {
        int i = 0;
        StringBuilder buf = new StringBuilder();
        while (i < line.length()) {
            // Bold: **text**
            if (i + 1 < line.length() && line.charAt(i) == '*' && line.charAt(i + 1) == '*') {
                flushText(flow, buf, PLAIN);
                int end = line.indexOf("**", i + 2);
                if (end > i) {
                    String boldText = line.substring(i + 2, end);
                    Text node = new Text(boldText);
                    node.setFont(Font.font(null, FontWeight.BOLD, 13));
                    flow.getChildren().add(node);
                    i = end + 2;
                } else {
                    buf.append("**");
                    i += 2;
                }
                continue;
            }
            // Italic: *text* (but not **)
            if (line.charAt(i) == '*' && (i == 0 || line.charAt(i - 1) != '*')
                    && (i + 1 >= line.length() || line.charAt(i + 1) != '*')) {
                flushText(flow, buf, PLAIN);
                int end = line.indexOf('*', i + 1);
                if (end > i) {
                    Text node = new Text(line.substring(i + 1, end));
                    node.setFont(Font.font(null, FontWeight.NORMAL, 13));
                    node.setStyle("-fx-font-style: italic;");
                    flow.getChildren().add(node);
                    i = end + 1;
                } else {
                    buf.append('*');
                    i++;
                }
                continue;
            }
            // Inline code: `text`
            if (line.charAt(i) == '`') {
                flushText(flow, buf, PLAIN);
                int end = line.indexOf('`', i + 1);
                if (end > i) {
                    Text node = new Text(line.substring(i + 1, end));
                    node.setFont(Font.font("Monospaced", 12));
                    node.setStyle("-fx-fill: -monet-primary; -fx-background-color: -monet-surface-container;");
                    flow.getChildren().add(node);
                    i = end + 1;
                } else {
                    buf.append('`');
                    i++;
                }
                continue;
            }
            buf.append(line.charAt(i));
            i++;
        }
        flushText(flow, buf, PLAIN);
    }

    private static void flushText(MarkdownMessageView flow, StringBuilder buf, int style) {
        if (buf.length() == 0) return;
        Text node = new Text(buf.toString());
        if (style == CODE) {
            node.setFont(Font.font("Monospaced", 12));
        }
        buf.setLength(0);
        flow.getChildren().add(node);
    }
}
