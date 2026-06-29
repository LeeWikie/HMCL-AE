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

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;

/// A single model configured under an {@link AiProviderProfile}.
///
/// Beyond the model id and an optional display alias, an entry carries optional
/// per-model **advanced** overrides (context window, max output, temperature,
/// reasoning effort) and **pricing** (per-million-token prices used to estimate
/// spend). Advanced overrides use sentinel "unset" values (`0` for the integer
/// fields, `< 0` for temperature, empty string for reasoning effort) meaning
/// "fall back to the global/provider default".
@NotNullByDefault
public final class AiModelEntry {

    /// Sentinel temperature meaning "no per-model override".
    public static final double TEMPERATURE_UNSET = -1.0;

    @SerializedName("id")
    private String id = "";

    @SerializedName("alias")
    private String alias = "";

    // ---- Advanced (0 / empty / <0 = use default) ----

    @SerializedName("contextWindow")
    private int contextWindow;

    @SerializedName("maxOutputTokens")
    private int maxOutputTokens;

    @SerializedName("temperature")
    private double temperature = TEMPERATURE_UNSET;

    @SerializedName("reasoningEffort")
    private String reasoningEffort = "";

    // ---- Pricing (per million tokens, 0 = unknown) ----

    @SerializedName("inputPricePerMillion")
    private double inputPricePerMillion;

    @SerializedName("outputPricePerMillion")
    private double outputPricePerMillion;

    @SerializedName("cacheWritePricePerMillion")
    private double cacheWritePricePerMillion;

    @SerializedName("cacheReadPricePerMillion")
    private double cacheReadPricePerMillion;

    /// Comma-separated input modalities the model accepts (e.g. "text", "text,image").
    @SerializedName("inputModalities")
    private String inputModalities = "text";

    /// Comma-separated output modalities the model produces (e.g. "text").
    @SerializedName("outputModalities")
    private String outputModalities = "text";

    /// Whether the model supports native tool/function calling.
    @SerializedName("supportsTools")
    private boolean supportsTools = true;

    /// Whether the model can read images (vision input).
    @SerializedName("supportsVision")
    private boolean supportsVision = false;

    /// Whether the model exposes a reasoning/thinking mode.
    @SerializedName("supportsReasoning")
    private boolean supportsReasoning = false;

    /// No-arg constructor for Gson.
    public AiModelEntry() {
    }

    /// Creates an entry with the given model id.
    public AiModelEntry(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias != null ? alias : "";
    }

    /// Returns the alias if set, otherwise the model id.
    public String getDisplayName() {
        return alias != null && !alias.isEmpty() ? alias : id;
    }

    public int getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(int contextWindow) {
        this.contextWindow = Math.max(0, contextWindow);
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = Math.max(0, maxOutputTokens);
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    /// Returns whether a per-model temperature override is set.
    public boolean hasTemperature() {
        return temperature >= 0;
    }

    public String getReasoningEffort() {
        return reasoningEffort != null ? reasoningEffort : "";
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort != null ? reasoningEffort : "";
    }

    public double getInputPricePerMillion() {
        return inputPricePerMillion;
    }

    public void setInputPricePerMillion(double v) {
        this.inputPricePerMillion = Math.max(0, v);
    }

    public double getOutputPricePerMillion() {
        return outputPricePerMillion;
    }

    public void setOutputPricePerMillion(double v) {
        this.outputPricePerMillion = Math.max(0, v);
    }

    public double getCacheWritePricePerMillion() {
        return cacheWritePricePerMillion;
    }

    public void setCacheWritePricePerMillion(double v) {
        this.cacheWritePricePerMillion = Math.max(0, v);
    }

    public double getCacheReadPricePerMillion() {
        return cacheReadPricePerMillion;
    }

    public void setCacheReadPricePerMillion(double v) {
        this.cacheReadPricePerMillion = Math.max(0, v);
    }

    public String getInputModalities() {
        return inputModalities == null ? "text" : inputModalities;
    }

    public void setInputModalities(String v) {
        this.inputModalities = v == null || v.isBlank() ? "text" : v.trim();
    }

    public String getOutputModalities() {
        return outputModalities == null ? "text" : outputModalities;
    }

    public void setOutputModalities(String v) {
        this.outputModalities = v == null || v.isBlank() ? "text" : v.trim();
    }

    public boolean isSupportsTools() {
        return supportsTools;
    }

    public void setSupportsTools(boolean v) {
        this.supportsTools = v;
    }

    public boolean isSupportsVision() {
        return supportsVision;
    }

    public void setSupportsVision(boolean v) {
        this.supportsVision = v;
    }

    public boolean isSupportsReasoning() {
        return supportsReasoning;
    }

    public void setSupportsReasoning(boolean v) {
        this.supportsReasoning = v;
    }

    /// Returns whether any non-zero price is configured.
    public boolean hasPricing() {
        return inputPricePerMillion > 0 || outputPricePerMillion > 0
                || cacheWritePricePerMillion > 0 || cacheReadPricePerMillion > 0;
    }

    /// Estimates the spend for a response from its token counts.
    public double computeCost(int inputTokens, int outputTokens,
                              int cacheWriteTokens, int cacheReadTokens) {
        return (inputTokens * inputPricePerMillion
                + outputTokens * outputPricePerMillion
                + cacheWriteTokens * cacheWritePricePerMillion
                + cacheReadTokens * cacheReadPricePerMillion) / 1_000_000.0;
    }
}
