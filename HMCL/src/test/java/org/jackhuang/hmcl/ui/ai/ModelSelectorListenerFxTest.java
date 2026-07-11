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

import org.jackhuang.hmcl.ai.AiProviderProfile;
import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.AiSessionStore;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.jackhuang.hmcl.ui.ai.AiMainPageFxTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// P2 (blueprint B1): setupModelSelector used to re-register its value listener on EVERY refresh
/// (constructor, every showChatView, settings callback), and the refresh's own setValue fired all
/// accumulated listeners — each run doing clearAgentCache()+persistAiSettings(). Asserts:
/// ① a refresh never fires the model-switch side effects (agent cache survives);
/// ② the listener count on valueProperty stays constant across refreshes (best-effort reflection);
/// ③ a REAL user selection still switches profile/model and clears the cache.
public final class ModelSelectorListenerFxTest {

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

    /// Counts listeners on a property via its ExpressionHelper, or null when the internals are
    /// inaccessible on this JVM (the behavioral assertions below don't depend on it).
    private static Integer tryCountListeners(Object property) {
        try {
            Field helperField = null;
            for (Class<?> c = property.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    helperField = c.getDeclaredField("helper");
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (helperField == null) return null;
            helperField.setAccessible(true);
            Object helper = helperField.get(property);
            if (helper == null) return 0;
            String simple = helper.getClass().getSimpleName();
            if (simple.startsWith("Single")) return 1;
            int total = 0;
            for (String name : new String[]{"invalidationSize", "changeSize"}) {
                try {
                    Field f = helper.getClass().getDeclaredField(name);
                    f.setAccessible(true);
                    total += f.getInt(helper);
                } catch (NoSuchFieldException ignored) {
                }
            }
            return total;
        } catch (Throwable t) {
            return null;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void refreshNeverAccumulatesListenersNorFiresSwitchSideEffects() throws Exception {
        AIMainPage page = showPage();
        AiSettings settings = (AiSettings) getField(page, "aiSettings");
        AiSessionStore store = (AiSessionStore) getField(page, "sessionStore");
        // A freshly created session, not whatever the constructor happened to auto-create/reuse as
        // "current" — isolates this test from ambient store state (see MessageActionsHoverFxTest).
        AiSession session = store.createSession();
        try {
            assertNotNull(session);

            LineSelectButton<String> selector = (LineSelectButton<String>) getField(page, "modelSelector");
            Integer countBefore = tryCountListeners(selector.valueProperty());

            // A cached agent is the canary: before P2, every refresh's setValue fired the accumulated
            // listeners, each of which cleared (shutdownNow'd) the agent cache.
            Map<String, Object> cache = (Map<String, Object>) getField(page, "agentCache");
            Object canary = new Object() {
            };
            // A real ChatAgent isn't needed — clearAgentCache would remove the entry; use presence as
            // the signal. (Map is typed Map<String,ChatAgent> at runtime-erased generics.)
            ((Map<String, Object>) cache).put("canary-session", canary);
            try {
                for (int i = 0; i < 3; i++) {
                    invokeFx(page, "refreshModelSelector");
                }
                assertSame(canary, cache.get("canary-session"),
                        "a pure refresh must NOT fire the model-switch listener "
                                + "(which clears the agent cache) — P2 regression");

                Integer countAfter = tryCountListeners(selector.valueProperty());
                if (countBefore != null && countAfter != null) {
                    assertEquals(countBefore, countAfter,
                            "listener count on valueProperty must not grow with refreshes (P2)");
                }
            } finally {
                cache.remove("canary-session");
            }

            // ③ a real user selection still works after the listener moved out of setupModelSelector.
            // Two models: the refresh itself pins the selector to model-a (latched, no side effects);
            // switching to model-b is then a REAL value change that must fire the listener.
            String originalSelected = settings.getSelectedProfileId();
            AiProviderProfile testProfile = new AiProviderProfile(
                    "fxtest-p2-profile", "FxTestProv", "openai-completions",
                    "http://localhost:1/v1", "sk-test", "fxtest-model-a",
                    List.of("fxtest-model-a", "fxtest-model-b"), true);
            settings.putProfile(testProfile);
            try {
                invokeFx(page, "refreshModelSelector");
                WaitForAsyncUtils.asyncFx(() -> selector.setValue("FxTestProv / fxtest-model-b"))
                        .get(10, TimeUnit.SECONDS);
                WaitForAsyncUtils.waitForFxEvents();
                assertEquals("fxtest-p2-profile", settings.getSelectedProfileId(),
                        "a genuine selection must still switch the active profile");
                assertEquals("fxtest-model-b", testProfile.getDefaultModelId(),
                        "a genuine selection must still set the default model");
            } finally {
                settings.removeProfile(testProfile.getId());
                WaitForAsyncUtils.asyncFx(() -> {
                    settings.setSelectedProfileId(originalSelected);
                    try {
                        settings.save();
                    } catch (Exception ignored) {
                    }
                    try {
                        invoke(page, "refreshModelSelector", new Class<?>[0]);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).get(10, TimeUnit.SECONDS);
                WaitForAsyncUtils.waitForFxEvents();
            }
        } finally {
            store.deleteSession(session.getId());
        }
    }
}
