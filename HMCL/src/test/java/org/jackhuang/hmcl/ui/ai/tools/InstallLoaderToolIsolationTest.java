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
package org.jackhuang.hmcl.ui.ai.tools;

import org.jackhuang.hmcl.setting.DefaultIsolationType;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// L4: AI installs (`install_loader`) used to skip the user's "default version isolation"
/// preset that the native wizard applies right after `buildAsync()` completes
/// (`VanillaInstallWizardProvider.finishVersionDownloadingAsync`). The post-install step is
/// extracted as [InstallLoaderTool#applyPostInstallDefaults]; these tests drive it against a
/// real throwaway profile ([ProfileFixture]) instead of a network install.
public final class InstallLoaderToolIsolationTest {

    @Test
    public void alwaysIsolationPresetIsAppliedToTheNewInstance() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            SettingsManager.getDefaultGameSettingsPresetOrCreate()
                    .defaultIsolationTypeProperty().setValue(DefaultIsolationType.ALWAYS);
            fx.createInstance("iso-always");

            InstallLoaderTool.applyPostInstallDefaults(fx.profile(), "iso-always");

            GameSettings.Instance setting = fx.repository().getInstanceGameSettings("iso-always");
            assertNotNull(setting, "isolation must create per-instance game settings");
            assertTrue(setting.getOverrideProperties().contains(GameSettings.PROPERTY_RUNNING_DIRECTORY),
                    "ALWAYS preset must isolate the freshly installed instance, like the native wizard");
        }
    }

    @Test
    public void neverIsolationPresetLeavesTheInstanceUnisolated() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            SettingsManager.getDefaultGameSettingsPresetOrCreate()
                    .defaultIsolationTypeProperty().setValue(DefaultIsolationType.NEVER);
            fx.createInstance("iso-never");

            InstallLoaderTool.applyPostInstallDefaults(fx.profile(), "iso-never");

            GameSettings.Instance setting = fx.repository().getInstanceGameSettings("iso-never");
            assertTrue(setting == null
                            || !setting.getOverrideProperties().contains(GameSettings.PROPERTY_RUNNING_DIRECTORY),
                    "NEVER preset must not force the running-directory override");
        }
    }

    @Test
    public void unknownInstanceIsANoOpInsteadOfAFailure() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            // Post-install defaults are best-effort: a vanished instance (or any runtime
            // hiccup) must never turn an already-successful install into a tool failure.
            assertDoesNotThrow(() ->
                    InstallLoaderTool.applyPostInstallDefaults(fx.profile(), "does-not-exist"));
        }
    }
}
