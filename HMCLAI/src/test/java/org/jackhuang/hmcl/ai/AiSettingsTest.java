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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public final class AiSettingsTest {

    /// Verifies that saving and loading round-trips correctly, including
    /// Base64 encoding of the API key so it is never stored in plain text.
    @Test
    public void testSaveLoadRoundTrip() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-test-");
        try {
            // --- Save phase ---
            AiSettings settings = new AiSettings(tempDir);
            settings.endpointProperty().set("https://custom.example.com/v1");
            settings.modelProperty().set("gpt-4-turbo");
            settings.maxTokensProperty().set(8000);
            settings.temperatureProperty().set(0.3);
            settings.apiKeyProperty().set("sk-supersecret-test-key-12345");

            settings.save();

            // Verify the file exists and does NOT contain the plain-text key.
            Path file = tempDir.resolve(AiSettings.FILE_NAME);
            assertTrue(Files.exists(file), "ai-settings.json should exist after save");
            String rawJson = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(rawJson.contains("sk-supersecret-test-key-12345"),
                    "Persisted file must not contain the plain-text API key");
            // It should contain a Base64-encoded version of the key.
            String expectedEncoded = Base64.getEncoder().encodeToString(
                    "sk-supersecret-test-key-12345".getBytes(StandardCharsets.UTF_8));
            assertTrue(rawJson.contains(expectedEncoded),
                    "Persisted file must contain the Base64-encoded API key");

            // --- Load phase (fresh instance) ---
            AiSettings loaded = new AiSettings(tempDir);
            loaded.load();

            assertEquals("https://custom.example.com/v1", loaded.getEndpoint());
            assertEquals("gpt-4-turbo", loaded.getModel());
            assertEquals(8000, loaded.getMaxTokens());
            assertEquals(0.3, loaded.getTemperature(), 0.0001);
            assertEquals("sk-supersecret-test-key-12345", loaded.getApiKey(),
                    "API key must round-trip as plain text in memory");
        } finally {
            // Clean up temp directory.
            try {
                Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                        });
            } catch (IOException ignored) {
            }
        }
    }

    /// Verifies that loading from a non-existent file keeps default values.
    @Test
    public void testDefaultsWhenFileMissing() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-test-");
        try {
            AiSettings settings = new AiSettings(tempDir);
            settings.load(); // No file exists

            assertEquals(LlmConfig.DEFAULT_ENDPOINT, settings.getEndpoint());
            assertEquals(LlmConfig.DEFAULT_API_KEY, settings.getApiKey());
            assertEquals(LlmConfig.DEFAULT_MODEL, settings.getModel());
            assertEquals(LlmConfig.DEFAULT_MAX_TOKENS, settings.getMaxTokens());
            assertEquals(LlmConfig.DEFAULT_TEMPERATURE, settings.getTemperature(), 0.0001);
            assertEquals(LlmConfig.DEFAULT_PROVIDER, settings.getProvider());
            assertEquals(LlmConfig.DEFAULT_CONTEXT_WINDOW, settings.getContextWindow());
            assertEquals(LlmConfig.DEFAULT_TOP_P, settings.getTopP(), 0.0001);
            assertTrue(settings.isStream());
        } finally {
            try {
                Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                        });
            } catch (IOException ignored) {
            }
        }
    }

    /// Verifies that an empty API key is persisted and loaded correctly.
    @Test
    public void testEmptyApiKey() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-test-");
        try {
            AiSettings settings = new AiSettings(tempDir);
            settings.apiKeyProperty().set("");
            settings.save();

            AiSettings loaded = new AiSettings(tempDir);
            loaded.load();

            assertEquals("", loaded.getApiKey());
        } finally {
            try {
                Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                        });
            } catch (IOException ignored) {
            }
        }
    }

    /// Verifies that a corrupted (non-Base64) API key value in the file is
    /// handled gracefully by treating it as empty.
    @Test
    public void testCorruptedApiKeyFallsBackToEmpty() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-test-");
        try {
            // Manually write a JSON file with an invalid Base64 apiKey.
            String badJson = "{\n" +
                    "  \"endpoint\": \"https://api.openai.com/v1/chat/completions\",\n" +
                    "  \"apiKey\": \"!!!not-valid-base64!!!\",\n" +
                    "  \"model\": \"gpt-4o-mini\",\n" +
                    "  \"maxTokens\": 4096,\n" +
                    "  \"temperature\": 0.7\n" +
                    "}";
            Files.createDirectories(tempDir);
            Files.writeString(tempDir.resolve(AiSettings.FILE_NAME), badJson, StandardCharsets.UTF_8);

            AiSettings loaded = new AiSettings(tempDir);
            loaded.load();

            assertEquals("", loaded.getApiKey(),
                    "Corrupted Base64 API key should fall back to empty string");
        } finally {
            try {
                Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                        });
            } catch (IOException ignored) {
            }
        }
    }

    /// Verifies that the new advanced parameters round-trip correctly through
    /// save and load, including seed, reasoning effort, stop sequences, and stream.
    @Test
    public void testAdvancedParametersRoundTrip() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-test-");
        try {
            AiSettings settings = new AiSettings(tempDir);
            settings.providerProperty().set("anthropic");
            settings.contextWindowProperty().set(200000);
            settings.maxOutputTokensProperty().set(64000);
            settings.topPProperty().set(0.95);
            settings.presencePenaltyProperty().set(0.5);
            settings.frequencyPenaltyProperty().set(0.3);
            settings.seedProperty().set(42L);
            settings.reasoningEffortProperty().set("medium");
            settings.streamProperty().set(false);
            settings.setStopSequences(List.of("END", "STOP"));

            settings.save();

            AiSettings loaded = new AiSettings(tempDir);
            loaded.load();

            assertEquals("anthropic", loaded.getProvider());
            assertEquals(200000, loaded.getContextWindow());
            assertEquals(64000, loaded.getMaxOutputTokens());
            assertEquals(0.95, loaded.getTopP(), 0.0001);
            assertEquals(0.5, loaded.getPresencePenalty(), 0.0001);
            assertEquals(0.3, loaded.getFrequencyPenalty(), 0.0001);
            assertEquals(Long.valueOf(42L), loaded.getSeed());
            assertEquals("medium", loaded.getReasoningEffort());
            assertFalse(loaded.isStream());
            assertEquals(List.of("END", "STOP"), loaded.getStopSequences());
        } finally {
            try {
                Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                        });
            } catch (IOException ignored) {
            }
        }
    }

    /// Verifies that null seed and empty reasoning effort round-trip correctly.
    @Test
    public void testNullableAdvancedFieldsRoundTrip() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-test-");
        try {
            AiSettings settings = new AiSettings(tempDir);
            settings.seedProperty().set(null);
            settings.reasoningEffortProperty().set("");
            settings.save();

            AiSettings loaded = new AiSettings(tempDir);
            loaded.load();

            assertNull(loaded.getSeed());
            assertEquals("", loaded.getReasoningEffort());
        } finally {
            try {
                Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                        });
            } catch (IOException ignored) {
            }
        }
    }

    /// Verifies that the stop sequences accessor returns an unmodifiable view
    /// and that setting null clears the list.
    @Test
    public void testStopSequencesImmutability() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-test-");
        try {
            AiSettings settings = new AiSettings(tempDir);
            settings.setStopSequences(List.of("A", "B"));
            assertEquals(List.of("A", "B"), settings.getStopSequences());

            // Setting null should clear the list.
            settings.setStopSequences(null);
            assertTrue(settings.getStopSequences().isEmpty());

            // The returned list should be unmodifiable.
            assertThrows(UnsupportedOperationException.class, () ->
                    settings.getStopSequences().add("X"));
        } finally {
            try {
                Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                        });
            } catch (IOException ignored) {
            }
        }
    }

    /// Verifies that old single-provider settings (no profiles field) are
    /// automatically migrated into a single provider profile on load.
    @Test
    public void testBackwardMigrationFromLegacySettings() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-test-");
        try {
            String plainKey = "sk-legacy-key-123";
            String encodedKey = Base64.getEncoder().encodeToString(
                    plainKey.getBytes(StandardCharsets.UTF_8));

            String legacyJson = "{\n"
                    + "  \"endpoint\": \"https://api.openai.com/v1/chat/completions\",\n"
                    + "  \"apiKey\": \"" + encodedKey + "\",\n"
                    + "  \"model\": \"gpt-4-turbo\",\n"
                    + "  \"provider\": \"openai\",\n"
                    + "  \"maxTokens\": 8000,\n"
                    + "  \"temperature\": 0.5\n"
                    + "}";
            Files.createDirectories(tempDir);
            Files.writeString(tempDir.resolve(AiSettings.FILE_NAME), legacyJson,
                    StandardCharsets.UTF_8);

            AiSettings settings = new AiSettings(tempDir);
            settings.load();

            assertFalse(settings.getProfiles().isEmpty(),
                    "A migration profile should have been created");
            assertEquals(1, settings.getProfiles().size(),
                    "Exactly one migration profile should exist");
            assertNotNull(settings.getSelectedProfileId(),
                    "A selected profile id should be set");

            AiProviderProfile profile = settings.getProfiles().get(0);
            assertEquals("Migrated Provider", profile.getDisplayName());
            assertEquals("gpt-4-turbo", profile.getDefaultModelId());

            assertEquals(plainKey, settings.getApiKey(),
                    "API key should be decoded to plain text");
            assertEquals("gpt-4-turbo", settings.getModel());
        } finally {
            try {
                Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                        });
            } catch (IOException ignored) {
            }
        }
    }

    /// Verifies round-trip save/load with multi-profile format.
    @Test
    public void testMultiProfileRoundTrip() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-test-");
        try {
            AiSettings settings = new AiSettings(tempDir);

            AiProviderProfile profile = new AiProviderProfile(
                    "profile-1", "Test OpenAI",
                    AiProtocolFamily.OPENAI_COMPLETIONS.getId(),
                    "https://api.openai.com/v1/chat/completions",
                    "sk-roundtrip-key", "gpt-4o",
                    List.of("gpt-4o", "gpt-4o-mini"), true);

            settings.setProfiles(List.of(profile));
            settings.setSelectedProfileId("profile-1");
            settings.save();

            AiSettings loaded = new AiSettings(tempDir);
            loaded.load();

            assertEquals(1, loaded.getProfiles().size());
            AiProviderProfile loadedProfile = loaded.getProfiles().get(0);
            assertEquals("profile-1", loadedProfile.getId());
            assertEquals("Test OpenAI", loadedProfile.getDisplayName());
            assertEquals(AiProtocolFamily.OPENAI_COMPLETIONS.getId(),
                    loadedProfile.getProtocolFamily());
            assertEquals("sk-roundtrip-key", loadedProfile.getApiKey());
            assertEquals("gpt-4o", loadedProfile.getDefaultModelId());
            assertEquals(2, loadedProfile.getCachedModels().size());
            assertTrue(loadedProfile.isEnabled());
            assertEquals("profile-1", loaded.getSelectedProfileId());
        } finally {
            try {
                Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                        });
            } catch (IOException ignored) {
            }
        }
    }

    /// Verifies profile management operations: add, update, remove.
    @Test
    public void testProfileManagement() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-test-");
        try {
            AiSettings settings = new AiSettings(tempDir);

            AiProviderProfile p1 = new AiProviderProfile(
                    "id-1", "Profile One",
                    AiProtocolFamily.OPENAI_COMPLETIONS.getId(),
                    "", "", null, List.of(), true);

            AiProviderProfile p2 = new AiProviderProfile(
                    "id-2", "Profile Two",
                    AiProtocolFamily.ANTHROPIC.getId(),
                    "", "", null, List.of(), true);

            settings.putProfile(p1);
            settings.putProfile(p2);

            assertEquals(2, settings.getProfiles().size());
            assertEquals("id-1", settings.getSelectedProfileId());

            AiProviderProfile p1Updated = new AiProviderProfile(
                    "id-1", "Profile One Updated",
                    AiProtocolFamily.OPENAI_REASONING.getId(),
                    "", "", null, List.of(), true);
            settings.putProfile(p1Updated);

            assertEquals(2, settings.getProfiles().size());
            assertEquals("Profile One Updated", settings.getProfiles().get(0).getDisplayName());

            assertTrue(settings.removeProfile("id-2"));
            assertEquals(1, settings.getProfiles().size());
            assertFalse(settings.removeProfile("non-existent"));

            settings.setSelectedProfileId("id-1");
            assertNotNull(settings.findSelectedProfile());
        } finally {
            try {
                Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                        });
            } catch (IOException ignored) {
            }
        }
    }

    /// Verifies that the new global AI behaviour settings round-trip correctly
    /// through save and load, including approval mode, title naming, and analysis toggles.
    @Test
    public void testGlobalBehaviorSettingsRoundTrip() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-test-");
        try {
            AiSettings settings = new AiSettings(tempDir);
            settings.titleNamingEnabledProperty().set(false);
            settings.titleNamingModelIdProperty().set("gpt-4o-title");
            settings.autoLogAnalysisEnabledProperty().set(false);
            settings.autoCrashAnalysisEnabledProperty().set(false);
            settings.toolCallDisplayEnabledProperty().set(false);
            settings.approvalModeProperty().set(AiApprovalMode.ASK.getId());
            settings.dangerousActionConfirmationEnabledProperty().set(false);

            settings.save();

            AiSettings loaded = new AiSettings(tempDir);
            loaded.load();

            assertFalse(loaded.isTitleNamingEnabled(),
                    "titleNamingEnabled should round-trip as false");
            assertEquals("gpt-4o-title", loaded.getTitleNamingModelId(),
                    "titleNamingModelId should round-trip");
            assertFalse(loaded.isAutoLogAnalysisEnabled(),
                    "autoLogAnalysisEnabled should round-trip as false");
            assertFalse(loaded.isAutoCrashAnalysisEnabled(),
                    "autoCrashAnalysisEnabled should round-trip as false");
            assertFalse(loaded.isToolCallDisplayEnabled(),
                    "toolCallDisplayEnabled should round-trip as false");
            assertEquals(AiApprovalMode.ASK.getId(), loaded.getApprovalMode(),
                    "approvalMode should round-trip as 'ask'");
            assertEquals(AiApprovalMode.ASK, loaded.getApprovalModeEnum(),
                    "getApprovalModeEnum should return ASK");
            assertFalse(loaded.isDangerousActionConfirmationEnabled(),
                    "dangerousActionConfirmationEnabled should round-trip as false");
        } finally {
            try {
                Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                        });
            } catch (IOException ignored) {
            }
        }
    }

    /// Verifies that all three AiApprovalMode values serialize and deserialize correctly.
    @Test
    public void testApprovalModeSerialization() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-test-");
        try {
            // Safe
            {
                AiSettings settings = new AiSettings(tempDir);
                settings.approvalModeProperty().set(AiApprovalMode.SAFE.getId());
                settings.save();

                AiSettings loaded = new AiSettings(tempDir);
                loaded.load();
                assertEquals(AiApprovalMode.SAFE.getId(), loaded.getApprovalMode());
                assertEquals(AiApprovalMode.SAFE, loaded.getApprovalModeEnum());
            }
            // Ask
            {
                AiSettings settings = new AiSettings(tempDir);
                settings.approvalModeProperty().set(AiApprovalMode.ASK.getId());
                settings.save();

                AiSettings loaded = new AiSettings(tempDir);
                loaded.load();
                assertEquals(AiApprovalMode.ASK.getId(), loaded.getApprovalMode());
                assertEquals(AiApprovalMode.ASK, loaded.getApprovalModeEnum());
            }
            // YOLO
            {
                AiSettings settings = new AiSettings(tempDir);
                settings.approvalModeProperty().set(AiApprovalMode.YOLO.getId());
                settings.save();

                AiSettings loaded = new AiSettings(tempDir);
                loaded.load();
                assertEquals(AiApprovalMode.YOLO.getId(), loaded.getApprovalMode());
                assertEquals(AiApprovalMode.YOLO, loaded.getApprovalModeEnum());
            }
        } finally {
            try {
                Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                        });
            } catch (IOException ignored) {
            }
        }
    }

    /// Verifies that the new global AI behaviour settings use the correct defaults
    /// when the settings file does not exist.
    @Test
    public void testDefaultsForNewGlobalSettings() throws IOException {
        Path tempDir = Files.createTempDirectory("hmcl-ai-test-");
        try {
            AiSettings settings = new AiSettings(tempDir);
            settings.load(); // No file exists

            assertTrue(settings.isTitleNamingEnabled(),
                    "titleNamingEnabled should default to true");
            assertEquals("", settings.getTitleNamingModelId(),
                    "titleNamingModelId should default to empty string");
            assertTrue(settings.isAutoLogAnalysisEnabled(),
                    "autoLogAnalysisEnabled should default to true");
            assertTrue(settings.isAutoCrashAnalysisEnabled(),
                    "autoCrashAnalysisEnabled should default to true");
            assertTrue(settings.isToolCallDisplayEnabled(),
                    "toolCallDisplayEnabled should default to true");
            assertEquals(AiApprovalMode.SAFE.getId(), settings.getApprovalMode(),
                    "approvalMode should default to 'safe'");
            assertEquals(AiApprovalMode.SAFE, settings.getApprovalModeEnum(),
                    "getApprovalModeEnum should default to SAFE");
            assertTrue(settings.isDangerousActionConfirmationEnabled(),
                    "dangerousActionConfirmationEnabled should default to true");
        } finally {
            try {
                Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                        });
            } catch (IOException ignored) {
            }
        }
    }
}
