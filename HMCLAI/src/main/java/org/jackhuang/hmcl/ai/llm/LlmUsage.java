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

/// Token usage for a single assistant response.
///
/// Values are either reported by the provider (when the model returns a usage
/// object) or estimated locally from character counts when no provider data is
/// available. The [`estimated`] flag distinguishes the two so the UI can mark
/// estimated figures.
///
/// Instances are immutable and serialized to JSON via Gson as part of the
/// owning [`LlmMessage`] so usage survives session reloads.
///
/// @see LlmMessage#getUsage()
@NotNullByDefault
public final class LlmUsage {

    /// Rough chars-per-token heuristic used when estimating without provider data.
    private static final int CHARS_PER_TOKEN = 4;

    @SerializedName("promptTokens")
    private int promptTokens;

    @SerializedName("completionTokens")
    private int completionTokens;

    @SerializedName("totalTokens")
    private int totalTokens;

    @SerializedName("estimated")
    private boolean estimated;

    /// No-arg constructor for Gson deserialization.
    LlmUsage() {
    }

    private LlmUsage(int promptTokens, int completionTokens, int totalTokens, boolean estimated) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.estimated = estimated;
    }

    /// Creates a usage instance from provider-reported counts.
    ///
    /// Any negative input is clamped to zero. When [totalTokens] is zero but the
    /// prompt/completion counts are present, the total is derived from their sum.
    ///
    /// @param promptTokens     prompt/input token count reported by the provider
    /// @param completionTokens completion/output token count reported by the provider
    /// @param totalTokens      total token count reported by the provider
    /// @return a non-estimated usage instance
    public static LlmUsage of(int promptTokens, int completionTokens, int totalTokens) {
        int prompt = Math.max(0, promptTokens);
        int completion = Math.max(0, completionTokens);
        int total = Math.max(0, totalTokens);
        if (total == 0) {
            total = prompt + completion;
        }
        return new LlmUsage(prompt, completion, total, false);
    }

    /// Estimates usage from message text using a chars-per-token heuristic.
    ///
    /// @param promptText     the full prompt/context text sent to the model
    /// @param completionText the assistant response text
    /// @return an estimated usage instance flagged with [`estimated`] = true
    public static LlmUsage estimate(String promptText, String completionText) {
        int prompt = promptText.length() / CHARS_PER_TOKEN;
        int completion = completionText.length() / CHARS_PER_TOKEN;
        return new LlmUsage(prompt, completion, prompt + completion, true);
    }

    /// Returns the prompt/input token count.
    public int getPromptTokens() {
        return promptTokens;
    }

    /// Returns the completion/output token count.
    public int getCompletionTokens() {
        return completionTokens;
    }

    /// Returns the total token count.
    public int getTotalTokens() {
        return totalTokens;
    }

    /// Returns whether these figures are locally estimated rather than
    /// provider-reported.
    public boolean isEstimated() {
        return estimated;
    }

    /// Returns whether this usage carries any non-zero token count worth showing.
    public boolean hasData() {
        return totalTokens > 0 || promptTokens > 0 || completionTokens > 0;
    }
}
