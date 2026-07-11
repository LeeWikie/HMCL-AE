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
package org.jackhuang.hmcl.ai.search;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableValue;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNullByDefault;
import com.google.gson.annotations.SerializedName;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

@NotNullByDefault
public final class AiSearchConfig {

    // Default to Bocha: a China-reachable search API (no proxy needed on the mainland), better for
    // the target audience than Tavily (US, requires a proxy). Bocha's endpoint is fixed, so the
    // user only needs to supply an API key.
    @SerializedName("provider")
    private String provider = "bocha";

    @SerializedName("endpoint")
    private String endpoint = "https://api.tavily.com/search";

    /// Deprecated singular key field from before providers got their own keys (pre-{@link #apiKeys}
    /// split). Only ever read once, by {@link #migrateLegacyApiKey()}, to carry an older config's
    /// key over to whichever provider was selected when it was saved; never written to again.
    @Deprecated
    @SerializedName("apiKey")
    private String legacyApiKey = "";

    /// API key per search provider. Each provider (Tavily, SearXNG, Bocha, Zhipu, ...) gets its own
    /// slot so switching the provider dropdown in settings never clobbers or exposes another
    /// provider's key — see {@link #getApiKey(SearchProvider)} / {@link #setApiKey(SearchProvider, String)}.
    @SerializedName("apiKeys")
    private Map<SearchProvider, String> apiKeys = new EnumMap<>(SearchProvider.class);

    @SerializedName("enabled")
    private boolean enabled = false;

    /// Observable mirror of {@link #enabled}, created lazily the first time {@link #enabledProperty()}
    /// is requested (seeded from the just-deserialized {@link #enabled} field). Lets the "启用搜索"
    /// settings toggle and the chat page's web-tool registration share ONE live source of truth: the
    /// single shared config instance's enabled state — so flipping the toggle registers/unregisters
    /// {@code web_search} on the next turn without a restart (陈旧态批 §3.4). {@code transient} so Gson
    /// never serializes the JavaFX property object; the persisted source of truth stays the plain
    /// {@link #enabled} field, which {@link #setEnabled(boolean)} keeps in lock-step.
    private transient @Nullable BooleanProperty enabledProperty;

    @SerializedName("maxResults")
    private int maxResults = 5;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    /// Resolves {@link #provider} (persisted lowercase, e.g. {@code "bocha"}) to a
    /// {@link SearchProvider}, or {@code null} if the id doesn't match any known provider (e.g. a
    /// hand-edited config file, or one written by a newer build that added a provider this one
    /// doesn't know about).
    @Nullable
    public SearchProvider resolveProvider() {
        return SearchProvider.fromId(provider.toUpperCase(Locale.ROOT));
    }

    /// Returns the API key stored for {@code provider}, or {@code ""} if unset / {@code provider}
    /// is {@code null}.
    public String getApiKey(@Nullable SearchProvider provider) {
        migrateLegacyApiKey();
        return provider == null ? "" : apiKeys().getOrDefault(provider, "");
    }

    /// Stores the API key for {@code provider}, leaving every other provider's key untouched. No-op
    /// if {@code provider} is {@code null}.
    public void setApiKey(@Nullable SearchProvider provider, String apiKey) {
        migrateLegacyApiKey();
        if (provider != null) apiKeys().put(provider, apiKey);
    }

    /// Defends against a hand-edited/corrupt config file that sets {@code "apiKeys": null}: Gson
    /// otherwise happily assigns {@code null} straight to the field, and every accessor above would
    /// then NPE instead of degrading to "no keys set".
    private Map<SearchProvider, String> apiKeys() {
        if (apiKeys == null) apiKeys = new EnumMap<>(SearchProvider.class);
        return apiKeys;
    }

    /// Convenience for the currently selected {@link #provider} (see {@link #resolveProvider()}).
    /// This is what the settings page's API Key dialog and {@link WebSearchTool} read/write, so
    /// switching the provider dropdown always shows and edits that provider's own key.
    public String getApiKey() {
        return getApiKey(resolveProvider());
    }

    /// @see #getApiKey()
    public void setApiKey(String apiKey) {
        setApiKey(resolveProvider(), apiKey);
    }

    public boolean isEnabled() {
        return enabledProperty != null ? enabledProperty.get() : enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabledProperty != null) enabledProperty.set(enabled);
    }

    /// Live observable of the search-enabled state (see {@link #enabledProperty}). The chat page
    /// binds web-tool registration to this (ANDed with the web-access toggle) so the toggle takes
    /// effect immediately; the settings toggle mutates the same property through
    /// {@link #setEnabled(boolean)}, keeping the two in sync with no restart and no second boolean.
    public ObservableValue<Boolean> enabledProperty() {
        if (enabledProperty == null) {
            enabledProperty = new SimpleBooleanProperty(this, "enabled", enabled);
        }
        return enabledProperty;
    }

    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int maxResults) { this.maxResults = maxResults; }

    /// One-time migration from the pre-split single {@code apiKey} field: a config saved by an
    /// older HMCL-AE build stored one key for whichever provider was selected at the time. Runs at
    /// most once per instance — {@link #legacyApiKey} is cleared afterward so it can't re-migrate
    /// (and overwrite a since-changed key) on a later call.
    private void migrateLegacyApiKey() {
        if (legacyApiKey != null && !legacyApiKey.isEmpty()) {
            SearchProvider p = resolveProvider();
            if (p != null && !apiKeys().containsKey(p)) {
                apiKeys().put(p, legacyApiKey);
            }
            legacyApiKey = "";
        }
    }
}
