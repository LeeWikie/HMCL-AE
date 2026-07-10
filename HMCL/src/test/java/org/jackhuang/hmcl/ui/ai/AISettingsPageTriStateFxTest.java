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
package org.jackhuang.hmcl.ui.ai;

import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import org.jackhuang.hmcl.ai.AiProviderProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// 测试连通性弹窗的分组复选框三态（2026-07-10 真机反馈 6a）：子项部分勾选时，
/// 分组/全选 checkbox 必须显示 indeterminate（半选），而不是二值化成全选/未选。
/// 直接驱动 AISettingsPage 的包级私有聚合函数（updateProviderBox / updateMasterBox），
/// 事件注入零 robot。
public final class AISettingsPageTriStateFxTest {

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
        System.setProperty("glass.win.uiScale", "100%");
        System.setProperty("prism.allowhidpi", "false");
        FxToolkit.registerPrimaryStage();
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> callable) throws Exception {
        return WaitForAsyncUtils.asyncFx(callable).get(10, TimeUnit.SECONDS);
    }

    @Test
    public void providerBoxShowsIndeterminateWhenSomeModelsSelected() throws Exception {
        boolean[] state = onFx(() -> {
            CheckBox provider = new CheckBox();
            provider.setAllowIndeterminate(true);
            CheckBox m1 = new CheckBox();
            CheckBox m2 = new CheckBox();
            m1.setSelected(true);
            m2.setSelected(false);
            AISettingsPage.updateProviderBox(provider, List.of(m1, m2));
            return new boolean[]{provider.isIndeterminate(), provider.isSelected()};
        });
        assertTrue(state[0], "部分勾选 → 组 checkbox 必须显示 indeterminate（半选）");
    }

    @Test
    public void providerBoxIsCheckedWhenAllSelectedAndUncheckedWhenNone() throws Exception {
        boolean[] all = onFx(() -> {
            CheckBox provider = new CheckBox();
            provider.setAllowIndeterminate(true);
            CheckBox m1 = new CheckBox();
            CheckBox m2 = new CheckBox();
            m1.setSelected(true);
            m2.setSelected(true);
            AISettingsPage.updateProviderBox(provider, List.of(m1, m2));
            return new boolean[]{provider.isIndeterminate(), provider.isSelected()};
        });
        assertFalse(all[0], "全选时不得是半选");
        assertTrue(all[1], "全选时组 checkbox 必须是选中");

        boolean[] none = onFx(() -> {
            CheckBox provider = new CheckBox();
            provider.setAllowIndeterminate(true);
            provider.setSelected(true); // start dirty to prove it is actively cleared
            CheckBox m1 = new CheckBox();
            CheckBox m2 = new CheckBox();
            AISettingsPage.updateProviderBox(provider, List.of(m1, m2));
            return new boolean[]{provider.isIndeterminate(), provider.isSelected()};
        });
        assertFalse(none[0], "全不选时不得是半选");
        assertFalse(none[1], "全不选时组 checkbox 必须是未选");
    }

    @Test
    public void masterSelectAllBoxShowsIndeterminateAcrossProviders() throws Exception {
        boolean[] state = onFx(() -> {
            CheckBox master = new CheckBox();
            master.setAllowIndeterminate(true);
            AiProviderProfile profile = new AiProviderProfile();
            CheckBox c1 = new CheckBox();
            CheckBox c2 = new CheckBox();
            CheckBox c3 = new CheckBox();
            c1.setSelected(true); // 1 of 3 selected → indeterminate
            List<AISettingsPage.TestRow> rows = List.of(
                    new AISettingsPage.TestRow(profile, "m1", c1, new Label(), c1),
                    new AISettingsPage.TestRow(profile, "m2", c2, new Label(), c2),
                    new AISettingsPage.TestRow(profile, "m3", c3, new Label(), c3));
            AISettingsPage.updateMasterBox(master, rows);
            return new boolean[]{master.isIndeterminate(), master.isSelected()};
        });
        assertTrue(state[0], "跨提供商聚合：部分勾选 → 全选 checkbox 必须半选");
    }
}
