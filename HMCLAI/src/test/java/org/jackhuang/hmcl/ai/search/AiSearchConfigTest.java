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
package org.jackhuang.hmcl.ai.search;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks in the per-provider API key split (was a single shared {@code apiKey} string that every
/// provider in the settings-page dropdown silently overwrote — see
/// {@link org.jackhuang.hmcl.ai.search.AiSearchConfig}): each {@link SearchProvider} now gets its
/// own slot in {@code apiKeys}, plus a one-shot migration path for configs saved by an older build.
public final class AiSearchConfigTest {

    // Mirrors the Gson instance AISettingsPage/AIMainPage actually persist AiSearchConfig with.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @Test
    void tavilyAndSearxngKeysAreIndependent() {
        AiSearchConfig config = new AiSearchConfig();
        config.setApiKey(SearchProvider.TAVILY, "tvly-aaa");
        assertEquals("", config.getApiKey(SearchProvider.SEARXNG)); // setting Tavily must not leak into SearXNG

        config.setApiKey(SearchProvider.SEARXNG, "sx-bbb");
        assertEquals("tvly-aaa", config.getApiKey(SearchProvider.TAVILY)); // ...and vice versa
        assertEquals("sx-bbb", config.getApiKey(SearchProvider.SEARXNG));

        // Overwriting one provider's key must not disturb the other's.
        config.setApiKey(SearchProvider.TAVILY, "tvly-ccc");
        assertEquals("tvly-ccc", config.getApiKey(SearchProvider.TAVILY));
        assertEquals("sx-bbb", config.getApiKey(SearchProvider.SEARXNG));
    }

    @Test
    void unsetProviderKeyDefaultsToEmptyString() {
        AiSearchConfig config = new AiSearchConfig();
        assertEquals("", config.getApiKey(SearchProvider.BOCHA));
        assertEquals("", config.getApiKey(SearchProvider.ZHIPU));
        assertEquals("", config.getApiKey(null));
    }

    /// This is what the settings page's API Key dialog + {@link WebSearchTool} actually call: the
    /// no-arg overloads that resolve against whichever provider is currently selected. Switching
    /// the provider dropdown must read/write that provider's own key, not a shared one.
    @Test
    void noArgAccessorsFollowTheCurrentlySelectedProvider() {
        AiSearchConfig config = new AiSearchConfig();

        config.setProvider("tavily");
        config.setApiKey("tvly-current");
        assertEquals("tvly-current", config.getApiKey());

        // Switching the dropdown to SearXNG must show/edit SearXNG's own (still-empty) key, not
        // Tavily's — this is exactly the bug being fixed.
        config.setProvider("searxng");
        assertEquals("", config.getApiKey());
        config.setApiKey("sx-current");
        assertEquals("sx-current", config.getApiKey());

        // Switching back to Tavily must still show the key set for it earlier, untouched by the
        // SearXNG edit in between.
        config.setProvider("tavily");
        assertEquals("tvly-current", config.getApiKey());
    }

    @Test
    void persistenceRoundTripKeepsEachProviderKeySeparate() {
        AiSearchConfig config = new AiSearchConfig();
        config.setApiKey(SearchProvider.TAVILY, "tvly-persist");
        config.setApiKey(SearchProvider.BOCHA, "sk-persist");
        config.setEnabled(true);
        config.setMaxResults(10);

        String json = GSON.toJson(config);
        // Locks in the persisted shape: a provider-keyed map, not a single shared field.
        assertTrue(json.contains("\"apiKeys\""));
        assertTrue(json.contains("\"TAVILY\""));
        assertTrue(json.contains("\"BOCHA\""));

        AiSearchConfig reloaded = GSON.fromJson(json, AiSearchConfig.class);
        assertEquals("tvly-persist", reloaded.getApiKey(SearchProvider.TAVILY));
        assertEquals("sk-persist", reloaded.getApiKey(SearchProvider.BOCHA));
        assertEquals("", reloaded.getApiKey(SearchProvider.SEARXNG)); // never set, stays empty
        assertEquals("", reloaded.getApiKey(SearchProvider.ZHIPU));
        assertTrue(reloaded.isEnabled());
        assertEquals(10, reloaded.getMaxResults());
    }

    /// A config saved by a pre-split HMCL-AE build only has the old singular {@code apiKey} field
    /// (no {@code apiKeys} map). That key belonged to whichever provider was selected at the time
    /// ("bocha" here) and must survive the format change instead of silently vanishing.
    @Test
    void legacySingleApiKeyFieldMigratesToTheProviderItBelongedTo() {
        String legacyJson = "{"
                + "\"provider\":\"bocha\","
                + "\"endpoint\":\"https://api.bochaai.com/v1/web/search\","
                + "\"apiKey\":\"sk-legacy-key\","
                + "\"enabled\":true,"
                + "\"maxResults\":5"
                + "}";
        AiSearchConfig config = GSON.fromJson(legacyJson, AiSearchConfig.class);

        assertEquals("sk-legacy-key", config.getApiKey()); // current provider (bocha) picks it up
        assertEquals("sk-legacy-key", config.getApiKey(SearchProvider.BOCHA));
        assertEquals("", config.getApiKey(SearchProvider.TAVILY)); // migration is scoped to bocha only

        // Switching away and back must not re-run the migration or lose the key: it now lives in
        // the per-provider map, independent of the (cleared) legacy field.
        config.setProvider("tavily");
        assertEquals("", config.getApiKey());
        config.setProvider("bocha");
        assertEquals("sk-legacy-key", config.getApiKey());
    }

    @Test
    void missingApiKeysMapDefaultsToAllProvidersEmpty() {
        // A config saved before the map existed at all, and without even the old apiKey field.
        String bareJson = "{\"provider\":\"tavily\",\"endpoint\":\"https://api.tavily.com/search\","
                + "\"enabled\":false,\"maxResults\":5}";
        AiSearchConfig config = GSON.fromJson(bareJson, AiSearchConfig.class);

        for (SearchProvider p : SearchProvider.values()) {
            assertEquals("", config.getApiKey(p));
        }
        assertEquals("", config.getApiKey());

        // And it must still be safely writable afterward (no lingering null map from deserialization).
        config.setApiKey(SearchProvider.TAVILY, "tvly-fresh");
        assertEquals("tvly-fresh", config.getApiKey(SearchProvider.TAVILY));
    }
}
