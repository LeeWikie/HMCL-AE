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
package org.jackhuang.hmcl.ai;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Enumerates the protocol families that an {@link AiProviderProfile} can belong to.
///
/// Each family carries:
/// - the default chat path suffix that gets appended when a user supplies only
///   a base endpoint (e.g. `api.openai.com`)
/// - whether the family is OpenAI-compatible and therefore supports `/v1/models`
///   listing
@NotNullByDefault
public enum AiProtocolFamily {

    /// Standard OpenAI chat completions protocol.
    OPENAI_COMPLETIONS("openai-completions", "/v1/chat/completions", true),

    /// OpenAI protocol used with reasoning-capable models (o-series, etc.).
    OPENAI_REASONING("openai-reasoning", "/v1/chat/completions", true),

    /// Anthropic Messages API.
    ANTHROPIC("anthropic", "/v1/messages", false),

    /// Generic REST API endpoint with no built-in model discovery.
    RESTAPI("restapi", null, false);

    private final String id;
    @Nullable
    private final String defaultPathSuffix;
    private final boolean openaiCompatible;

    AiProtocolFamily(String id, @Nullable String defaultPathSuffix, boolean openaiCompatible) {
        this.id = id;
        this.defaultPathSuffix = defaultPathSuffix;
        this.openaiCompatible = openaiCompatible;
    }

    /// Returns the short identifier string (e.g. `"openai-completions"`).
    public String getId() {
        return id;
    }

    /// Returns the default chat path suffix, or `null` for families that do not
    /// auto-append a suffix (such as {@link #RESTAPI}).
    @Nullable
    public String getDefaultPathSuffix() {
        return defaultPathSuffix;
    }

    /// Returns `true` when this family speaks the OpenAI HTTP protocol and
    /// therefore supports `/v1/models` model listing.
    public boolean isOpenaiCompatible() {
        return openaiCompatible;
    }

    /// Looks up a family by its string identifier.
    ///
    /// @param id the family id string
    /// @return the matching family, or `null` if unknown
    @Nullable
    public static AiProtocolFamily fromId(String id) {
        for (AiProtocolFamily f : values()) {
            if (f.id.equals(id)) {
                return f;
            }
        }
        return null;
    }
}
