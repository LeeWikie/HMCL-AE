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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/// "自动命名模型" (title naming model) resolution + persistence:
/// - blank / unset = Auto → resolves to `null` (follow the current chat model);
/// - `"<profileId>::<modelId>"` resolves against the configured profiles;
/// - a bare model id resolves to the first profile carrying that model;
/// - stale selections (profile or model gone) fall back to Auto instead of failing;
/// - the value round-trips through save()/load().
public final class AiSettingsTitleNamingModelTest {

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
        return new AiSettings(Files.createTempDirectory("hmcl-ai-title-test-"));
    }

    @Test
    public void blankValueResolvesToAuto() throws IOException {
        AiSettings settings = freshSettings();
        settings.putProfile(profileWithModels("P1", "gpt-4o-mini"));
        assertEquals("", settings.getTitleNamingModel(), "default must be blank (= Auto)");
        assertNull(settings.resolveTitleNamingModel(), "blank must resolve to Auto (null)");
        settings.titleNamingModelProperty().set("   ");
        assertNull(settings.resolveTitleNamingModel(), "whitespace-only must also resolve to Auto");
    }

    @Test
    public void profileQualifiedValueResolvesToThatProfileAndModel() throws IOException {
        AiSettings settings = freshSettings();
        AiProviderProfile p1 = profileWithModels("P1", "shared-model", "p1-only");
        AiProviderProfile p2 = profileWithModels("P2", "shared-model");
        settings.putProfile(p1);
        settings.putProfile(p2);

        settings.titleNamingModelProperty().set(p2.getId() + "::shared-model");
        AiSettings.TitleNamingSelection sel = settings.resolveTitleNamingModel();
        assertNotNull(sel);
        assertEquals(p2.getId(), sel.profile().getId(),
                "the qualified form must pick the NAMED profile even when another has the same model id");
        assertEquals("shared-model", sel.modelId());
    }

    @Test
    public void bareModelIdResolvesToFirstProfileCarryingIt() throws IOException {
        AiSettings settings = freshSettings();
        AiProviderProfile p1 = profileWithModels("P1", "p1-only");
        AiProviderProfile p2 = profileWithModels("P2", "deepseek-chat");
        settings.putProfile(p1);
        settings.putProfile(p2);

        settings.titleNamingModelProperty().set("deepseek-chat");
        AiSettings.TitleNamingSelection sel = settings.resolveTitleNamingModel();
        assertNotNull(sel);
        assertEquals(p2.getId(), sel.profile().getId());
        assertEquals("deepseek-chat", sel.modelId());
    }

    @Test
    public void staleSelectionFallsBackToAuto() throws IOException {
        AiSettings settings = freshSettings();
        AiProviderProfile p1 = profileWithModels("P1", "gpt-4o-mini");
        settings.putProfile(p1);

        settings.titleNamingModelProperty().set("no-such-profile::gpt-4o-mini");
        assertNull(settings.resolveTitleNamingModel(), "unknown profile id → Auto");

        settings.titleNamingModelProperty().set(p1.getId() + "::deleted-model");
        assertNull(settings.resolveTitleNamingModel(), "model no longer on the profile → Auto");

        settings.titleNamingModelProperty().set("nowhere-model");
        assertNull(settings.resolveTitleNamingModel(), "bare id not on any profile → Auto");

        settings.titleNamingModelProperty().set(p1.getId() + "::");
        assertNull(settings.resolveTitleNamingModel(), "empty model id after separator → Auto");
    }

    @Test
    public void valueRoundTripsThroughSaveAndLoad() throws IOException {
        Path dir = Files.createTempDirectory("hmcl-ai-title-test-");
        AiSettings settings = new AiSettings(dir);
        AiProviderProfile p1 = profileWithModels("P1", "glm-4-flash");
        settings.putProfile(p1);
        settings.titleNamingModelProperty().set(p1.getId() + "::glm-4-flash");
        settings.save();

        AiSettings reloaded = new AiSettings(dir);
        reloaded.load();
        assertEquals(p1.getId() + "::glm-4-flash", reloaded.getTitleNamingModel(),
                "titleNamingModel must be serialized and restored");
        AiSettings.TitleNamingSelection sel = reloaded.resolveTitleNamingModel();
        assertNotNull(sel);
        assertEquals("glm-4-flash", sel.modelId());

        // Auto (blank) must survive a round-trip as blank, not as a literal "null".
        reloaded.titleNamingModelProperty().set("");
        reloaded.save();
        AiSettings again = new AiSettings(dir);
        again.load();
        assertEquals("", again.getTitleNamingModel());
        assertNull(again.resolveTitleNamingModel());
    }
}
