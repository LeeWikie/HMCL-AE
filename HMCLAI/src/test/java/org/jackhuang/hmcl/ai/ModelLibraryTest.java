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
package org.jackhuang.hmcl.ai;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks in the bundled model library: null/empty/unknown lookups return null (caller applies its
/// own defaults), the catalog is large, specific stable ids resolve with the expected context
/// window, and the conservative normalization fallback (case + `vendor/` prefix) hits.
public final class ModelLibraryTest {

    @Test
    void nullEmptyAndUnknownLookupsReturnNull() {
        assertNull(ModelLibrary.find(null), "null id must not NPE and must return null");
        assertNull(ModelLibrary.find(""), "empty id returns null");
        assertNull(ModelLibrary.find("definitely-not-a-real-model-id-xyz-123"),
                "unknown id returns null even after normalization");
    }

    @Test
    void bundledLibraryLoadsAndResolvesKnownModels() {
        Set<String> ids = ModelLibrary.getInstance().knownModelIds();
        assertNotNull(ids);
        assertTrue(ids.size() > 1000, "the bundled model library should carry the expanded catalog (>1000 entries)");

        ModelLibrary.ModelInfo deepseek = ModelLibrary.find("deepseek-v4-pro");
        assertNotNull(deepseek, "deepseek-v4-pro must resolve (this is the user-reported miss)");
        assertEquals(1_000_000, deepseek.getContextWindow(), "deepseek-v4-pro has a 1M context window");

        assertNotNull(ModelLibrary.find("gpt-3.5-turbo"), "a classic id like gpt-3.5-turbo must resolve");

        ModelLibrary.ModelInfo opus = ModelLibrary.find("claude-opus-4-8");
        assertNotNull(opus, "claude-opus-4-8 must resolve");
        assertTrue(opus.getContextWindow() > 0, "claude-opus-4-8 must carry a positive context window");
    }

    @Test
    void conservativeNormalizationHits() {
        ModelLibrary.ModelInfo mixedCase = ModelLibrary.find("DeepSeek-v4-Pro");
        assertNotNull(mixedCase, "case-insensitive normalization should resolve DeepSeek-v4-Pro");
        assertEquals(1_000_000, mixedCase.getContextWindow(), "normalized hit carries deepseek-v4-pro's 1M context");

        ModelLibrary.ModelInfo vendorPrefixed = ModelLibrary.find("deepseek/deepseek-v4-pro");
        assertNotNull(vendorPrefixed, "stripping the vendor/ prefix should resolve deepseek/deepseek-v4-pro");
        assertEquals(1_000_000, vendorPrefixed.getContextWindow(), "vendor-prefixed hit carries the 1M context");
    }
}
