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
package org.jackhuang.hmcl.ui.ai;

import com.jfoenix.controls.JFXButton;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ai.AiProviderProfile;
import org.jackhuang.hmcl.ai.AiSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.jackhuang.hmcl.ui.ai.AiMainPageFxTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Empty state (A14/C-16). The suggestion-chip block was removed outright (2026-07-11 user
/// feedback): the empty state is now just the icon + title, plus a "配置模型服务" CTA that appears
/// ONLY while no usable model service is configured (the functional way forward, not a chip).
/// Event/direct-method injection only (A7).
public final class EmptyStateFxTest {

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
        System.setProperty("glass.win.uiScale", "100%");
        System.setProperty("prism.allowhidpi", "false");
        FxToolkit.registerPrimaryStage();
        useIsolatedConfigDirectory();
        ensureSettingsManagerLoaded();
        prepareFirstUseMarkers();
    }

    @AfterAll
    static void tearDownToolkit() throws Exception {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            FxToolkit.cleanupStages();
        }
        restoreRealConfigDirectory();
    }

    @BeforeEach
    void discardExceptionsLeakedByUnrelatedEarlierTests() {
        WaitForAsyncUtils.clearExceptions();
    }

    /// The no-provider CTA is shown ONLY when nothing usable is configured, and hidden the moment a
    /// usable provider exists — the empty state's single conditional block after the chip removal.
    @Test
    public void ctaVisibleOnlyWhenUnconfigured() throws Exception {
        AIMainPage page = showPage();
        AiSettings settings = (AiSettings) getField(page, "aiSettings");
        List<AiProviderProfile> originalProfiles = settings.getProfiles();
        try {
            // No usable provider → CTA visible.
            WaitForAsyncUtils.asyncFx(() -> settings.setProfiles(List.of())).get(10, TimeUnit.SECONDS);
            invokeFx(page, "updateEmptyState");

            VBox noProviderBox = (VBox) getField(page, "noProviderBox");
            assertTrue(noProviderBox.isVisible() && noProviderBox.isManaged(),
                    "unconfigured: the CTA block must be shown");
            assertNotNull(findButtonByText(noProviderBox, "配置模型服务"),
                    "the CTA block must contain the settings button");

            // One enabled provider WITH a cached model (setupModelSelector's own criterion, C-16)
            // → CTA gone.
            AiProviderProfile profile = new AiProviderProfile();
            profile.setDisplayName("Test Provider");
            profile.setEnabled(true);
            profile.setCachedModels(List.of("test-model"));
            WaitForAsyncUtils.asyncFx(() -> settings.setProfiles(List.of(profile))).get(10, TimeUnit.SECONDS);
            invokeFx(page, "updateEmptyState");

            assertFalse(noProviderBox.isVisible() || noProviderBox.isManaged(),
                    "configured: the CTA block must be hidden");
        } finally {
            WaitForAsyncUtils.asyncFx(() -> settings.setProfiles(originalProfiles)).get(10, TimeUnit.SECONDS);
            invokeFx(page, "updateEmptyState");
        }
    }

    /// 2026-07-11: the "试试问：" suggestion-chip block was removed. The empty state must contain no
    /// suggestion chips (native border buttons); the only button that may appear is the CTA (a
    /// raised button, NOT a `jfx-button-border` chip).
    @Test
    public void emptyStateHasNoSuggestionChips() throws Exception {
        AIMainPage page = showPage();
        VBox emptyState = (VBox) getField(page, "emptyState");
        java.util.List<JFXButton> buttons = new java.util.ArrayList<>();
        collectButtons(emptyState, buttons);
        for (JFXButton btn : buttons) {
            assertFalse(btn.getStyleClass().contains("jfx-button-border"),
                    "the empty state must not contain any suggestion chips (removed 2026-07-11)");
        }
    }

    private static void collectButtons(Node root, java.util.List<JFXButton> out) {
        if (root instanceof JFXButton btn) {
            out.add(btn);
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectButtons(child, out);
            }
        }
    }

    private static JFXButton findButtonByText(Node root, String text) {
        if (root instanceof JFXButton btn && text.equals(btn.getText())) {
            return btn;
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                JFXButton found = findButtonByText(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
