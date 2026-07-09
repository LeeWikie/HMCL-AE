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
package org.jackhuang.hmcl.ai.agent;

import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.langchain4j.AiChatClient;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Lightweight strategy for producing a fallback session title by truncating the first user
/// message.
///
/// ## Note: this is NOT the real "auto-name sessions" feature
///
/// The actually-wired auto-title feature is {@code AiSettings#isAutoTitleEnabled()} /
/// {@code AIMainPage#maybeAutoTitle}, which asks the model itself (via
/// {@code ChatAgent#suggestTitle}) for a concise title after the first exchange. This class'
/// {@link #maybeAutoTitle} is a separate, simpler, first-message-truncation-only helper that is
/// not currently invoked from anywhere in the app; a previous "AI-powered naming" tier planned for
/// it (driven by now-removed `AiSettings` title-naming settings) was never implemented beyond a
/// scaffolding placeholder and has been deleted rather than wired up, since {@code suggestTitle}
/// already delivers the real thing.
///
/// ## Usage
///
/// Call {@link #maybeAutoTitle(AiSession, String, AiSettings, AiChatClient)}
/// after adding the first user message to the session. The method truncates it to
/// {@value #MAX_TITLE_LENGTH} characters and writes the title to the session via
/// {@link AiSession#setTitle(String)}.
@NotNullByDefault
public final class AiTitleNamingStrategy {

    /// Maximum length of a fallback title derived from the first user message.
    static final int MAX_TITLE_LENGTH = 50;

    /// Examines the session and, if the first user message has just been added,
    /// generates an appropriate title by truncating it (see the class doc — there is no
    /// AI-powered tier here).
    ///
    /// @param session      the session to title; must not be `null`
    /// @param userMessage  the user's message text; must not be `null`
    /// @param settings     the AI settings to check title-naming flags
    /// @param client       the AI chat client for LLM-based naming, or `null`
    ///                     when no client is available
    public static void maybeAutoTitle(AiSession session, String userMessage,
                                       AiSettings settings,
                                       @Nullable AiChatClient client) {
        // If the session already has a non-empty title, do not overwrite.
        String currentTitle = session.getTitle();
        if (currentTitle != null && !currentTitle.isEmpty()) {
            return;
        }

        String title = fallbackTitle(userMessage);
        session.setTitle(title);
    }

    /// Produces a fallback title by truncating the first user message.
    ///
    /// @param userMessage the user's message text; must not be `null`
    /// @return a title of at most {@value #MAX_TITLE_LENGTH} characters
    public static String fallbackTitle(String userMessage) {
        if (userMessage.length() <= MAX_TITLE_LENGTH) {
            return userMessage;
        }
        return userMessage.substring(0, MAX_TITLE_LENGTH);
    }
}
