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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks in the bundled model library: null/empty/unknown lookups return null (caller applies its
/// own defaults), and a known model id resolves to metadata with a sane context window.
public final class ModelLibraryTest {

    @Test
    void nullEmptyAndUnknownLookupsReturnNull() {
        assertNull(ModelLibrary.find(null), "null id must not NPE and must return null");
        assertNull(ModelLibrary.find(""), "empty id returns null");
        assertNull(ModelLibrary.find("definitely-not-a-real-model-id-xyz-123"), "unknown id returns null (strict match)");
    }

    @Test
    void bundledLibraryLoadsAndResolvesKnownModels() {
        Set<String> ids = ModelLibrary.getInstance().knownModelIds();
        assertNotNull(ids);
        assertFalse(ids.isEmpty(), "the bundled model library JSON should load with entries");

        String anyId = ids.iterator().next();
        ModelLibrary.ModelInfo info = ModelLibrary.find(anyId);
        assertNotNull(info, "a known id from knownModelIds() must resolve");
        assertTrue(info.getContextWindow() > 0, "a catalogued model should carry a positive context window");
    }
}
