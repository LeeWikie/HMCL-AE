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
package org.jackhuang.hmcl.ui.ai.tools;

import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.AiSessionStore;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Exports a chat session to a self-contained Markdown file under
/// `{localConfigDir}/ai-exports/`, mirroring desktop LLM clients (e.g. Cherry Studio)
/// that let users save a conversation for sharing or archival.
///
/// The launcher's own session storage ([`AiSessionStore`]) is reused as the source of
/// truth, so the export always matches what the user sees in the chat list — no copy of
/// the history is kept inside the tool. The current session is exported by default; pass
/// `session_id` to export a specific one.
///
/// Output is plain Markdown (the agent itself is the model, so no LLM round-trip is
/// needed): a YAML-ish header with the title/timestamps followed by one section per
/// message. System messages are skipped by default to keep the transcript readable.
///
/// Permission level: CONTROLLED_WRITE. It only ever writes a new file inside the
/// dedicated `ai-exports` directory and never touches game files.
@NotNullByDefault
public final class ExportConversationTool implements Tool {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneId.systemDefault());

    private final AiSessionStore store;

    public ExportConversationTool(AiSessionStore store) {
        this.store = store;
    }

    @Override
    public String getName() {
        return "export_conversation";
    }

    @Override
    public String getDescription() {
        return "Exports a chat conversation to a Markdown (.md) file under the ai-exports folder, "
                + "for sharing or archiving. Parameters: 'session_id' (optional, the conversation id; "
                + "defaults to the current conversation), 'include_system' (optional boolean, default false — "
                + "whether to include hidden system/instruction messages). Returns the absolute path of the "
                + "written file.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String sessionId = InstanceToolSupport.string(parameters, "session_id");
        boolean includeSystem = InstanceToolSupport.bool(parameters, "include_system");

        AiSession session;
        try {
            session = sessionId != null ? store.getSession(sessionId) : store.getCurrentSession();
        } catch (Throwable e) {
            return ToolResult.failure("Failed to access the conversation store: " + e.getMessage());
        }
        if (session == null) {
            return ToolResult.failure(sessionId != null
                    ? "No conversation found with id: " + sessionId
                    : "There is no active conversation to export.");
        }

        List<LlmMessage> messages = session.getMessages();
        if (messages.isEmpty()) {
            return ToolResult.failure("The conversation is empty; nothing to export.");
        }

        String title = (session.getTitle() != null && !session.getTitle().isEmpty())
                ? session.getTitle() : "Untitled conversation";

        StringBuilder md = new StringBuilder();
        md.append("# ").append(title).append("\n\n");
        md.append("- Conversation id: `").append(session.getId()).append("`\n");
        md.append("- Created: ").append(STAMP.format(session.getCreatedAt())).append('\n');
        md.append("- Updated: ").append(STAMP.format(session.getUpdatedAt())).append('\n');
        md.append("- Exported: ").append(STAMP.format(java.time.Instant.now())).append("\n\n");
        md.append("---\n\n");

        int exported = 0;
        for (LlmMessage message : messages) {
            String role = message.getRole();
            String content = message.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            if (!includeSystem && "system".equalsIgnoreCase(role)) {
                continue;
            }
            md.append("## ").append(roleHeading(role)).append("\n\n");
            md.append(content.strip()).append("\n\n");
            exported++;
        }

        if (exported == 0) {
            return ToolResult.failure("The conversation has no visible messages to export "
                    + "(set include_system=true to include system messages).");
        }

        Path dir = SettingsManager.localConfigDirectory().resolve("ai-exports");
        Path file = dir.resolve(sanitize(title) + "-" + FILE_STAMP.format(java.time.Instant.now()) + ".md");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, md.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ToolResult.failure("Failed to write the export file: " + e.getMessage());
        }

        return ToolResult.success("Exported " + exported + " message(s) to:\n" + file.toAbsolutePath());
    }

    private static String roleHeading(String role) {
        switch (role == null ? "" : role.toLowerCase(Locale.ROOT)) {
            case "user":
                return "User";
            case "assistant":
                return "Assistant";
            case "system":
                return "System";
            default:
                return role == null || role.isEmpty() ? "Message" : role;
        }
    }

    /// Reduces a free-form title to a filesystem-safe slug (ASCII letters, digits, dash,
    /// underscore), collapsing runs of other characters into single underscores.
    private static String sanitize(String title) {
        String slug = title.trim().replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) {
            slug = "conversation";
        }
        return slug.length() > 60 ? slug.substring(0, 60) : slug;
    }
}
