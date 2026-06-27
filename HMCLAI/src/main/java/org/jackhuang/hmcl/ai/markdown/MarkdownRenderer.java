package org.jackhuang.hmcl.ai.markdown;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Arrays;
import java.util.List;

/// Renders Markdown text to styled HTML suitable for embedding in a JavaFX WebView.
///
/// Uses the commonmark-java library with GFM extensions (tables, autolink, strikethrough).
/// The returned HTML is wrapped in a full document with inline CSS that adapts to
/// HMCL's theme colours.
@NotNullByDefault
public final class MarkdownRenderer {

    private static final Parser PARSER;
    private static final HtmlRenderer RENDERER;

    static {
        List<Extension> extensions = Arrays.asList(
                TablesExtension.create(),
                AutolinkExtension.create(),
                StrikethroughExtension.create()
        );
        PARSER = Parser.builder().extensions(extensions).build();
        RENDERER = HtmlRenderer.builder().extensions(extensions).build();
    }

    private MarkdownRenderer() {}

    /// Parses markdown text and returns an HTML body string.
    public static String renderToHtml(String markdown) {
        Node document = PARSER.parse(markdown != null ? markdown : "");
        return RENDERER.render(document);
    }

    /// Wraps HTML body content in a full HTML document with inline CSS that adapts to
    /// HMCL's theme. When {@code darkMode} is true, dark-theme colours are used.
    public static String wrapHtmlDocument(String bodyHtml, boolean darkMode) {
        String bg = darkMode ? "#1e1e2e" : "#ffffff";
        String fg = darkMode ? "#cdd6f4" : "#1e1e2e";
        String codeBg = darkMode ? "#313244" : "#f4f4f5";
        String border = darkMode ? "#45475a" : "#e0e0e0";
        String muted = darkMode ? "#a6adc8" : "#6c7086";

        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                + "<link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/"
                + (darkMode ? "github-dark.min.css" : "github.min.css") + "\">"
                + "<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js\"><\\/script>"
                + "<script>document.addEventListener('DOMContentLoaded',function(){hljs.highlightAll();});<\\/script>"
                + "<style>"
                + "html,body{margin:0;padding:8px 12px;background:" + bg + ";color:" + fg
                + ";font-size:13px;line-height:1.65;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif;}"
                + "pre,code{font-family:'JetBrains Mono','Fira Code','Cascadia Code',Consolas,monospace;font-size:12px;}"
                + "pre{background:" + codeBg + ";border:1px solid " + border + ";border-radius:8px;padding:12px 14px;overflow-x:auto;}"
                + "code{background:" + codeBg + ";border-radius:4px;padding:1px 4px;}"
                + "pre code{background:transparent;padding:0;}"
                + "table{border-collapse:collapse;width:100%;margin:8px 0;}"
                + "th,td{border:1px solid " + border + ";padding:6px 10px;text-align:left;}"
                + "th{background:" + codeBg + ";}"
                + "blockquote{border-left:3px solid #2196f3;margin:8px 0;padding:4px 12px;color:" + muted + ";}"
                + "a{color:#2196f3;}"
                + "ul,ol{padding-left:20px;}"
                + "img{max-width:100%;}"
                + "h1,h2,h3,h4{line-height:1.3;margin:12px 0 4px;}"
                + "h1{font-size:18px;}h2{font-size:16px;}h3{font-size:14px;}"
                + "p{margin:4px 0;}"
                + "</style></head><body>" + bodyHtml + "</body></html>";
    }

    /// Quick heuristic to determine whether text contains Markdown formatting
    /// that would benefit from HTML rendering. Returns {@code false} for plain
    /// prose so callers can fall back to a lightweight JavaFX Label.
    public static boolean containsMarkdownSyntax(String text) {
        if (text == null || text.isEmpty()) return false;
        // Code blocks or inline code
        if (text.contains("```") || text.contains("`")) return true;
        // Headers
        if (text.matches("(?s).*^#{1,6}\\s.*")) return true;
        // Tables
        if (text.contains("|---") || text.contains("| ---")) return true;
        // Bold / italic
        if (text.contains("**") || text.contains("__") || text.contains("*") || text.contains("_")) return true;
        // Lists
        if (text.matches("(?s).*^[\\-*+]\\s.*")) return true;
        if (text.matches("(?s).*^\\d+\\.\\s.*")) return true;
        // Links or images
        if (text.contains("](") || text.contains("![")) return true;
        // Blockquotes
        if (text.matches("(?s).*^>\\s.*")) return true;
        return false;
    }
}
