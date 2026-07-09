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

import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// Shared scaffolding for FX tests that need a REAL {@link AIMainPage} (blueprint B1 tests:
/// StreamingGuardFxTest / ChatDrawerPersistenceFxTest / TokenBatchingFxTest / DraftOwnershipFxTest
/// / ModelSelectorListenerFxTest). The page is an application-level singleton in production, so
/// its constructor touches the real per-workspace config directory
/// ({@code SettingsManager.localConfigDirectory()}, i.e. `.hmcl` under the test working dir) —
/// {@link #prepareFirstUseMarkers()} pre-accepts the first-use dialogs so constructing the page
/// never queues a modal dialog (which would throw without a decorator stage and poison the FX
/// exception queue for every later waitForFxEvents()).
final class AiMainPageFxTestSupport {

    private AiMainPageFxTestSupport() {
    }

    /// Seeds SettingsManager.launcherSettings so lazily-animated containers don't throw
    /// "Configuration hasn't been loaded" — same technique as SettingsTabRefreshFxTest.
    static void ensureSettingsManagerLoaded() throws ReflectiveOperationException {
        Field field = SettingsManager.class.getDeclaredField("launcherSettings");
        field.setAccessible(true);
        if (field.get(null) == null) {
            field.set(null, new LauncherSettings());
        }
    }

    /// Pre-accepts the test-phase risk notice and the privacy consent in the REAL config dir the
    /// page constructor reads, so runFirstUseDialogs() is a guaranteed no-op during tests.
    static void prepareFirstUseMarkers() throws Exception {
        Path dir = SettingsManager.localConfigDirectory();
        Files.createDirectories(dir);
        AiSettings settings = new AiSettings(dir);
        try {
            settings.load();
        } catch (Exception ignored) {
        }
        if (!settings.isAiRiskNoticeAccepted()) {
            settings.setAiRiskNoticeAccepted(true);
            settings.save();
        }
        Path consent = dir.resolve("ai-privacy-consent");
        if (!Files.exists(consent)) {
            Files.writeString(consent, java.time.Instant.now().toString());
        }
    }

    /// Builds an AIMainPage inside a fresh scene root on the FX thread and returns it.
    static AIMainPage showPage() throws Exception {
        AtomicReference<AIMainPage> ref = new AtomicReference<>();
        FxToolkit.setupSceneRoot(() -> {
            AIMainPage page = new AIMainPage();
            ref.set(page);
            StackPane root = new StackPane(page);
            root.setPrefSize(1100, 750);
            return root;
        });
        FxToolkit.showStage();
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }

    // ---- tiny reflection helpers (page internals are deliberately private) ----------------

    static Object getField(Object target, String name) throws ReflectiveOperationException {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        return f.get(target);
    }

    static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    /// Invokes a private no-arg method ON THE FX THREAD and waits for it.
    static void invokeFx(Object target, String name) throws Exception {
        WaitForAsyncUtils.asyncFx(() -> {
            try {
                invoke(target, name, new Class<?>[0]);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }
}
