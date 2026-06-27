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

import static org.junit.jupiter.api.Assertions.*;

/// Tests for {@link AiModelPreset}, particularly the cross-provider
/// capability lookup via {@link AiModelPreset#findByModelId(String)}.
public final class AiModelPresetTest {

    /// Find a known model across the provider registry.
    @Test
    public void testFindKnownModel() {
        AiModelPreset preset = AiModelPreset.findByModelId("gpt-4o");
        assertNotNull(preset, "gpt-4o should be found in the preset registry");
        assertEquals("openai", preset.getProvider());
        assertEquals("GPT-4o", preset.getDisplayName());
        assertEquals(128000, preset.getDefaultContextWindow());
        assertEquals(16384, preset.getDefaultMaxOutput());
        assertFalse(preset.supportsReasoning());
    }

    /// Find a reasoning-capable model.
    @Test
    public void testFindReasoningModel() {
        AiModelPreset preset = AiModelPreset.findByModelId("o4-mini");
        assertNotNull(preset, "o4-mini should be found");
        assertTrue(preset.supportsReasoning(),
                "o4-mini should support reasoning");
    }

    /// Find an Anthropic model.
    @Test
    public void testFindAnthropicModel() {
        AiModelPreset preset = AiModelPreset.findByModelId("claude-sonnet-4-20250514");
        assertNotNull(preset, "claude-sonnet-4-20250514 should be found");
        assertEquals("anthropic", preset.getProvider());
        assertEquals(200000, preset.getDefaultContextWindow());
    }

    /// Find a DeepSeek model.
    @Test
    public void testFindDeepSeekModel() {
        AiModelPreset preset = AiModelPreset.findByModelId("deepseek-chat");
        assertNotNull(preset);
        assertEquals("deepseek", preset.getProvider());
    }

    /// Unknown model id returns null (remains usable as custom model).
    @Test
    public void testUnknownModelReturnsNull() {
        AiModelPreset preset = AiModelPreset.findByModelId("my-custom-model-v2");
        assertNull(preset, "Unknown model should return null");
    }

    /// Null input returns null.
    @Test
    public void testNullModelIdReturnsNull() {
        assertNull(AiModelPreset.findByModelId(null));
    }

    /// Empty input returns null.
    @Test
    public void testEmptyModelIdReturnsNull() {
        assertNull(AiModelPreset.findByModelId(""));
    }

    /// Verify preset immutability — all fields read back as constructed.
    @Test
    public void testPresetFieldAccessors() {
        AiModelPreset preset = new AiModelPreset(
                "test-provider", "Test Display",
                "test-model-id", 100000, 4096, true);

        assertEquals("test-provider", preset.getProvider());
        assertEquals("Test Display", preset.getDisplayName());
        assertEquals("test-model-id", preset.getModelId());
        assertEquals(100000, preset.getDefaultContextWindow());
        assertEquals(4096, preset.getDefaultMaxOutput());
        assertTrue(preset.supportsReasoning());
    }
}
