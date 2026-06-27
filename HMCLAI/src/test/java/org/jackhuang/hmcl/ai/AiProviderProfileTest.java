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

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for {@link AiProviderProfile}.
public final class AiProviderProfileTest {

    /// Default constructor creates a profile with a non-null id.
    @Test
    public void testDefaultConstructor() {
        AiProviderProfile profile = new AiProviderProfile();
        assertNotNull(profile.getId());
        assertFalse(profile.getId().isEmpty());
        assertEquals(AiProtocolFamily.OPENAI_COMPLETIONS.getId(), profile.getProtocolFamily());
        assertTrue(profile.isEnabled());
    }

    /// Full constructor sets all fields.
    @Test
    public void testFullConstructor() {
        List<String> models = Arrays.asList("gpt-4o", "gpt-4o-mini");
        AiProviderProfile profile = new AiProviderProfile(
                "test-id", "Test Profile",
                AiProtocolFamily.ANTHROPIC.getId(),
                "https://api.anthropic.com/v1/messages",
                "sk-key", "claude-sonnet-4-20250514",
                models, false);

        assertEquals("test-id", profile.getId());
        assertEquals("Test Profile", profile.getDisplayName());
        assertEquals(AiProtocolFamily.ANTHROPIC.getId(), profile.getProtocolFamily());
        assertEquals("https://api.anthropic.com/v1/messages", profile.getEndpoint());
        assertEquals("sk-key", profile.getApiKey());
        assertEquals("claude-sonnet-4-20250514", profile.getDefaultModelId());
        assertEquals(2, profile.getCachedModels().size());
        assertFalse(profile.isEnabled());
    }

    /// getEffectiveModelId returns default when set.
    @Test
    public void testEffectiveModelIdReturnsDefault() {
        AiProviderProfile profile = new AiProviderProfile();
        profile.setDefaultModelId("gpt-4o");
        profile.setCachedModels(Arrays.asList("gpt-4o-mini", "gpt-4.1"));

        assertEquals("gpt-4o", profile.getEffectiveModelId());
    }

    /// getEffectiveModelId falls back to first cached model.
    @Test
    public void testEffectiveModelIdFallbackToCached() {
        AiProviderProfile profile = new AiProviderProfile();
        profile.setDefaultModelId(null);
        profile.setCachedModels(Arrays.asList("gpt-4o-mini", "gpt-4.1"));

        assertEquals("gpt-4o-mini", profile.getEffectiveModelId());
    }

    /// getEffectiveModelId returns null when nothing is available.
    @Test
    public void testEffectiveModelIdReturnsNullWhenEmpty() {
        AiProviderProfile profile = new AiProviderProfile();
        profile.setDefaultModelId(null);
        profile.setCachedModels(Collections.emptyList());

        assertNull(profile.getEffectiveModelId());
    }

    /// getCachedModels returns a defensive copy.
    @Test
    public void testCachedModelsDefensiveCopy() {
        AiProviderProfile profile = new AiProviderProfile();
        profile.setCachedModels(Arrays.asList("a", "b"));

        List<String> models = profile.getCachedModels();
        assertEquals(2, models.size());

        // Verify it's unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> models.add("c"));
    }

    /// Setters update values.
    @Test
    public void testSetterUpdates() {
        AiProviderProfile profile = new AiProviderProfile();
        profile.setDisplayName("Updated");
        profile.setProtocolFamily(AiProtocolFamily.RESTAPI.getId());
        profile.setEndpoint("http://localhost:8080");
        profile.setApiKey("new-key");
        profile.setEnabled(false);

        assertEquals("Updated", profile.getDisplayName());
        assertEquals(AiProtocolFamily.RESTAPI.getId(), profile.getProtocolFamily());
        assertEquals("http://localhost:8080", profile.getEndpoint());
        assertEquals("new-key", profile.getApiKey());
        assertFalse(profile.isEnabled());
    }
}
