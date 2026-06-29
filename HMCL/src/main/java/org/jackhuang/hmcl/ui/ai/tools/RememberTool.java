package org.jackhuang.hmcl.ui.ai.tools;

import org.jackhuang.hmcl.ai.remember.RememberStore;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/// AI tool to store a fact into the global memory store (hermes-style file-based memory).
///
/// The model can call `remember` with a title, content, and optional tags so it
/// persists information across conversations. On recall, the store does full-text
/// search — no embedding model needed.
@NotNullByDefault
public final class RememberTool implements Tool {

    private final RememberStore store;

    public RememberTool(RememberStore store) {
        this.store = store;
    }

    @Override
    public String getName() {
        return "remember";
    }

    @Override
    public String getDescription() {
        return "Stores a fact into the global AI memory that persists across conversations. "
                + "Parameters: title (required, short slug), content (required, markdown), "
                + "tags (optional, comma-separated list of labels). "
                + "Use this to remember user preferences, decisions, or discovered facts. "
                + "To retrieve memories, use the recall tool.";
    }

    @SuppressWarnings("unchecked")
    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String title = (String) parameters.getOrDefault("title", "");
        String content = (String) parameters.getOrDefault("content", "");
        Object tagsObj = parameters.get("tags");

        if (title.isEmpty() || content.isEmpty()) {
            return ToolResult.failure("Both title and content are required to remember something.");
        }

        List<String> tags;
        if (tagsObj instanceof List) {
            tags = new ArrayList<>((List<String>) tagsObj);
        } else if (tagsObj instanceof String && !((String) tagsObj).isEmpty()) {
            tags = Arrays.asList(((String) tagsObj).split("\\s*,\\s*"));
        } else {
            tags = new ArrayList<>();
        }

        try {
            store.init();
            RememberStore.Entry entry = store.remember(title, tags, content);
            return ToolResult.success("✓ Remembered: " + title
                    + (tags.isEmpty() ? "" : " (tags: " + String.join(", ", tags) + ")")
                    + " — stored at " + entry.getFile().getFileName());
        } catch (IOException e) {
            return ToolResult.failure("Failed to remember: " + e.getMessage());
        }
    }
}
