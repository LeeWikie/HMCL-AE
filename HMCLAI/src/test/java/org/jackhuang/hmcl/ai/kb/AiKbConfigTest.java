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
package org.jackhuang.hmcl.ai.kb;

import com.google.gson.Gson;
import org.jackhuang.hmcl.ai.AiModelEntry;
import org.jackhuang.hmcl.ai.AiProtocolFamily;
import org.jackhuang.hmcl.ai.AiProviderProfile;
import org.jackhuang.hmcl.ai.AiSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/// {@link AiKbConfig}: defaults, retrieval-param clamping (setter AND getter, since Gson bypasses
/// setters), the enabled↔observable lock-step, embedding-model resolution (qualified / bare / stale),
/// the {@link AiKbConfig#isValid(AiSettings)} tool-registration gate, and Gson round-trip.
public final class AiKbConfigTest {

    private static AiProviderProfile profileWithModels(String name, String... modelIds) {
        AiProviderProfile profile = new AiProviderProfile();
        profile.setDisplayName(name);
        profile.setProtocolFamily(AiProtocolFamily.OPENAI_COMPLETIONS.getId());
        for (String id : modelIds) {
            profile.putModel(new AiModelEntry(id));
        }
        return profile;
    }

    private static AiSettings freshSettings() throws IOException {
        return new AiSettings(Files.createTempDirectory("hmcl-ai-kb-test-"));
    }

    @Test
    public void defaults() {
        AiKbConfig kb = new AiKbConfig();
        assertFalse(kb.isEnabled());
        assertEquals(KbSourceMode.REMOTE_HTTP, kb.getSourceMode());
        assertEquals("https://agentexperience.online", kb.getEndpoint());
        assertEquals("", kb.getLocalIndexPath());
        assertEquals("", kb.getEmbeddingModelRef());
        assertEquals(5, kb.getTopK());
        assertEquals(20, kb.getFusionTopK());
    }

    @Test
    public void retrievalParamsClampInSetter() {
        AiKbConfig kb = new AiKbConfig();
        kb.setTopK(999);
        assertEquals(20, kb.getTopK());
        kb.setTopK(0);
        assertEquals(1, kb.getTopK());
        kb.setFusionTopK(9999);
        assertEquals(50, kb.getFusionTopK());
        kb.setFusionTopK(-3);
        assertEquals(1, kb.getFusionTopK());
    }

    @Test
    public void handEditedOutOfRangeClampsOnRead() {
        // Gson sets the field directly, bypassing the setter — the getter must still clamp.
        AiKbConfig kb = new Gson().fromJson("{\"topK\":999,\"fusionTopK\":-7}", AiKbConfig.class);
        assertEquals(20, kb.getTopK());
        assertEquals(1, kb.getFusionTopK());
    }

    @Test
    public void enabledStaysInSyncWithObservable() {
        AiKbConfig kb = new AiKbConfig();
        assertEquals(Boolean.FALSE, kb.enabledProperty().getValue());
        kb.setEnabled(true);
        assertTrue(kb.isEnabled());
        assertEquals(Boolean.TRUE, kb.enabledProperty().getValue());
        kb.setEnabled(false);
        assertEquals(Boolean.FALSE, kb.enabledProperty().getValue());
    }

    @Test
    public void stringFieldsTrimmed() {
        AiKbConfig kb = new AiKbConfig();
        kb.setEndpoint("  https://x.test  ");
        assertEquals("https://x.test", kb.getEndpoint());
        kb.setLocalIndexPath("  C:/idx  ");
        assertEquals("C:/idx", kb.getLocalIndexPath());
        kb.setEmbeddingModelRef("  p::m  ");
        assertEquals("p::m", kb.getEmbeddingModelRef());
    }

    @Test
    public void nullSourceModeTolerated() {
        AiKbConfig kb = new AiKbConfig();
        kb.setSourceMode(null);
        assertEquals(KbSourceMode.REMOTE_HTTP, kb.getSourceMode());
    }

    @Test
    public void resolveEmbeddingModelQualifiedBareAndStale() throws IOException {
        AiSettings settings = freshSettings();
        AiProviderProfile p1 = profileWithModels("P1", "shared-embed", "p1-only");
        AiProviderProfile p2 = profileWithModels("P2", "shared-embed");
        settings.putProfile(p1);
        settings.putProfile(p2);
        AiKbConfig kb = new AiKbConfig();

        assertNull(kb.resolveEmbeddingModel(settings), "blank ref → null");

        kb.setEmbeddingModelRef(p2.getId() + "::shared-embed");
        AiKbConfig.ResolvedEmbeddingModel r = kb.resolveEmbeddingModel(settings);
        assertNotNull(r);
        assertEquals(p2.getId(), r.profile().getId(), "qualified form picks the NAMED profile");
        assertEquals("shared-embed", r.model().getId());

        kb.setEmbeddingModelRef("p1-only");
        r = kb.resolveEmbeddingModel(settings);
        assertNotNull(r);
        assertEquals(p1.getId(), r.profile().getId(), "bare id resolves to the profile carrying it");
        assertEquals("p1-only", r.model().getId());

        kb.setEmbeddingModelRef("no-such-profile::shared-embed");
        assertNull(kb.resolveEmbeddingModel(settings), "unknown profile → null");
        kb.setEmbeddingModelRef(p1.getId() + "::deleted");
        assertNull(kb.resolveEmbeddingModel(settings), "named profile without the model → null");
        kb.setEmbeddingModelRef("nowhere");
        assertNull(kb.resolveEmbeddingModel(settings), "bare id on no profile → null");
        kb.setEmbeddingModelRef(p1.getId() + "::");
        assertNull(kb.resolveEmbeddingModel(settings), "empty model id after :: → null");
    }

    @Test
    public void isValidGate() throws IOException {
        AiSettings settings = freshSettings();
        AiProviderProfile p1 = profileWithModels("P1", "text-embedding-3-small");
        settings.putProfile(p1);
        AiKbConfig kb = new AiKbConfig();

        // REMOTE_HTTP just needs a non-blank endpoint (the default endpoint is set → valid).
        assertTrue(kb.isValid(settings));
        kb.setEndpoint("");
        assertFalse(kb.isValid(settings), "REMOTE_HTTP with blank endpoint is invalid");

        // LOCAL_INDEX needs both an index path AND a resolvable embedding model.
        kb.setSourceMode(KbSourceMode.LOCAL_INDEX);
        assertFalse(kb.isValid(settings), "LOCAL_INDEX without path/model");
        kb.setLocalIndexPath("C:/idx");
        assertFalse(kb.isValid(settings), "LOCAL_INDEX with path but no model");
        kb.setEmbeddingModelRef(p1.getId() + "::text-embedding-3-small");
        assertTrue(kb.isValid(settings), "LOCAL_INDEX with path + resolvable model");
    }

    @Test
    public void gsonRoundTrip() {
        Gson gson = new Gson();
        AiKbConfig kb = new AiKbConfig();
        kb.setEnabled(true);
        kb.setSourceMode(KbSourceMode.LOCAL_INDEX);
        kb.setEndpoint("https://kb.test");
        kb.setLocalIndexPath("C:/idx");
        kb.setEmbeddingModelRef("prof::text-embedding-3-small");
        kb.setTopK(8);
        kb.setFusionTopK(30);

        AiKbConfig back = gson.fromJson(gson.toJson(kb), AiKbConfig.class);

        assertTrue(back.isEnabled(), "enabled survives via the plain field (transient property recreated lazily)");
        assertEquals(KbSourceMode.LOCAL_INDEX, back.getSourceMode());
        assertEquals("https://kb.test", back.getEndpoint());
        assertEquals("C:/idx", back.getLocalIndexPath());
        assertEquals("prof::text-embedding-3-small", back.getEmbeddingModelRef());
        assertEquals(8, back.getTopK());
        assertEquals(30, back.getFusionTopK());
        assertEquals(Boolean.TRUE, back.enabledProperty().getValue(),
                "lazy observable reflects the deserialized field on first access");
    }
}
