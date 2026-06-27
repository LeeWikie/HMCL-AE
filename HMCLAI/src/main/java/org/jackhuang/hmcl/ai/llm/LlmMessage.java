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
package org.jackhuang.hmcl.ai.llm;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;

/// Represents a single message in a chat completion conversation.
///
/// Each message has a [`role`] (one of `"system"`, `"user"`, or `"assistant"`)
/// and the text [`content`]. Instances are immutable and designed to be
/// serialized directly to the OpenAI chat completions JSON format via Gson.
///
/// @see LlmClient
@NotNullByDefault
public final class LlmMessage {

    @SerializedName("role")
    private String role;

    @SerializedName("content")
    private String content;

    /// Package-private no-arg constructor for Gson deserialization.
    LlmMessage() {
    }

    /// Creates a message with the given role and content.
    ///
    /// @param role    the role of the message sender; must not be `null`
    /// @param content the text content of the message; must not be `null`
    public LlmMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    /// Returns the role of the message sender (`"system"`, `"user"`, or `"assistant"`).
    public String getRole() {
        return role;
    }

    /// Returns the text content of the message.
    public String getContent() {
        return content;
    }
}
