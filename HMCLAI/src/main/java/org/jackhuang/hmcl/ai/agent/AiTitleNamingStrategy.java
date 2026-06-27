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

/// Lightweight strategy for generating and updating AI session titles.
///
/// ## Title generation tiers
///
/// 1. **AI-powered naming** (future): when {@link AiSettings#isTitleNamingEnabled()}
///    is `true` and a suitable title-naming model is available, the first user
///    message is sent through the LLM to produce a descriptive title.
/// 2. **First-message fallback**: the first user message is truncated to
///    {@value #MAX_TITLE_LENGTH} characters and used as the title directly.
///
/// ## Usage
///
/// Call {@link #maybeAutoTitle(AiSession, String, AiSettings, AiChatClient)}
/// after adding the first user message to the session. The method applies the
/// appropriate tier based on the current settings and writes the title to
/// the session via {@link AiSession#setTitle(String)}.
///
/// ## Implementation note
///
/// The AI-powered tier is currently a scaffolding placeholder. When
/// implemented, it will use the configured title-naming model id (if any)
/// with a dedicated short prompt to generate a concise title.
@NotNullByDefault
public final class AiTitleNamingStrategy {

    /// Maximum length of a fallback title derived from the first user message.
    static final int MAX_TITLE_LENGTH = 50;

    /// Examines the session and, if the first user message has just been added,
    /// generates an appropriate title.
    ///
    /// The fallback behaviour (truncating the first user message) always works
    /// regardless of {@link AiSettings#isTitleNamingEnabled()}.
    /// AI-powered naming is a planned future enhancement.
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
