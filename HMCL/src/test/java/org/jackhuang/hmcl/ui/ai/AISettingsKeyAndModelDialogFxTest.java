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

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.ComponentSublist;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// FX tests for the alpha settings polish (2026-07-11 用户反馈):
///  1. Search/provider API Key is an inline masked field + eye reveal ({@link AISettingsPage.MaskedKeyField}):
///     masked by default, the eye toggles cleartext, and re-binding to another provider's key keeps it masked.
///  2. The 配置模型 dialog's loosened spacing: 22px grid gaps, 8px caption→field, 22px capability row,
///     22/20/20 section padding — and the two 高级/定价 sections open collapsed (content not yet in the scene).
///
/// Event injection only, no physical robot (A7 discipline).
public final class AISettingsKeyAndModelDialogFxTest {

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
        System.setProperty("glass.win.uiScale", "100%");
        System.setProperty("prism.allowhidpi", "false");
        // AnimationUtils' static init (reached via ComponentSublistWrapper CSS/skin) reads
        // SettingsManager.settings(), which throws in a bare TestFX harness — seed it first
        // (same reflection technique as CollapseHeaderFxTest).
        ensureSettingsManagerLoaded();
        FxToolkit.registerPrimaryStage();
    }

    private static void ensureSettingsManagerLoaded() throws ReflectiveOperationException {
        Field field = SettingsManager.class.getDeclaredField("launcherSettings");
        field.setAccessible(true);
        if (field.get(null) == null) {
            LauncherSettings settings = new LauncherSettings();
            settings.animationDisabledProperty().set(true);
            field.set(null, settings);
        }
    }

    @BeforeEach
    void clearLeakedExceptions() {
        WaitForAsyncUtils.clearExceptions();
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> callable) throws Exception {
        return WaitForAsyncUtils.asyncFx(callable).get(10, TimeUnit.SECONDS);
    }

    // ---- 1. inline masked API-key field ----

    @Test
    public void maskedKeyFieldStartsMaskedAndEyeToggles() throws Exception {
        boolean[] s = onFx(() -> {
            AISettingsPage.MaskedKeyField f = new AISettingsPage.MaskedKeyField("sk-secret-123", "prompt");
            boolean maskedByDefault = !f.isRevealed() && f.masked.isVisible() && !f.shown.isVisible();
            f.toggleReveal();
            boolean revealed = f.isRevealed() && f.shown.isVisible() && !f.masked.isVisible();
            f.toggleReveal();
            boolean maskedAgain = !f.isRevealed() && f.masked.isVisible() && !f.shown.isVisible();
            return new boolean[]{maskedByDefault, revealed, maskedAgain};
        });
        assertTrue(s[0], "默认掩码态：masked 可见、shown 隐藏");
        assertTrue(s[1], "点眼睛 → 明文：shown 可见、masked 隐藏");
        assertTrue(s[2], "再点眼睛 → 回到掩码态");
    }

    @Test
    public void maskedKeyFieldRebindKeepsMaskedAndUpdatesValue() throws Exception {
        Object[] r = onFx(() -> {
            AISettingsPage.MaskedKeyField f = new AISettingsPage.MaskedKeyField("provider-a-key", "prompt");
            f.toggleReveal(); // even if the user had revealed it...
            f.setText("provider-b-key"); // switching provider re-binds
            return new Object[]{f.getText(), f.masked.getText()};
        });
        assertEquals("provider-b-key", r[0], "切 provider 后回填新服务商的 key");
        assertEquals("provider-b-key", r[1], "掩码框内容同步为新 key");
    }

    @Test
    public void cleanApiKeyStripsAllWhitespace() {
        // 粘贴清洗：内联字段 commit 与 provider 编辑 accept 共用此函数，去掉所有空白（含中缝的
        // 不可见换行/空格）——否则会变成用户看不见的 401。
        assertEquals("sk-withspacesand-newline",
                AISettingsPage.cleanApiKey(" sk-with spaces\nand-newline "));
        assertEquals("abc123", AISettingsPage.cleanApiKey("a b\tc\r\n1 2 3"));
        assertEquals("", AISettingsPage.cleanApiKey(null));
        assertEquals("", AISettingsPage.cleanApiKey("   \n\t "));
    }

    // ---- 2. model dialog loosened spacing ----

    @Test
    public void modelParamGridUsesLoosenedGapsAndPadding() throws Exception {
        double[] vals = onFx(() -> {
            GridPane g = AISettingsPage.modelParamGrid();
            Insets p = g.getPadding();
            return new double[]{g.getVgap(), g.getHgap(), p.getTop(), p.getRight(), p.getBottom(), p.getLeft()};
        });
        assertEquals(22, vals[0], 0.01, "advGrid/priceGrid vgap == 22");
        assertEquals(22, vals[1], 0.01, "advGrid/priceGrid hgap == 22");
        assertEquals(22, vals[2], 0.01, "section padding top == 22");
        assertEquals(20, vals[3], 0.01, "section padding right == 20");
        assertEquals(20, vals[4], 0.01, "section padding bottom == 20");
        assertEquals(20, vals[5], 0.01, "section padding left == 20");
    }

    @Test
    public void captionedFieldAndCapabilityRowSpacing() throws Exception {
        double[] vals = onFx(() -> {
            VBox captioned = AISettingsPage.captionedField("cap", new Label("x"));
            HBox caps = AISettingsPage.modelCapabilityRow(new CheckBox("a"), new CheckBox("b"), new CheckBox("c"));
            return new double[]{captioned.getSpacing(), caps.getSpacing()};
        });
        assertEquals(8, vals[0], 0.01, "captionedField caption→field spacing == 8");
        assertEquals(22, vals[1], 0.01, "模型能力复选框行 spacing == 22");
    }

    @Test
    public void modelSectionsStartCollapsed() throws Exception {
        // Build the two sections exactly as editModel wraps them, render, and confirm they open
        // collapsed: the header rows are laid out, but the content grids are NOT yet attached to
        // the scene (ComponentSublistWrapper only mounts the sublist on first expand).
        GridPane advGrid = onFx(AISettingsPage::modelParamGrid);
        Object[] rendered = onFx(() -> {
            ComponentSublist advPane = new ComponentSublist();
            advPane.setTitle("高级设置");
            advPane.getContent().setAll(advGrid);
            ComponentSublist pricePane = new ComponentSublist();
            pricePane.setTitle("定价设置");
            pricePane.getContent().setAll(new GridPane());
            ComponentList collapsibles = new ComponentList();
            collapsibles.getContent().addAll(advPane, pricePane);
            StackPane root = new StackPane(collapsibles);
            root.setPrefSize(520, 400);
            new javafx.scene.Scene(root);
            root.applyCss();
            root.layout();
            Set<Node> headers = collapsibles.lookupAll(".options-sublist-header");
            return new Object[]{headers.size(), advGrid.getScene() == null};
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(2, rendered[0], "两个小节的折叠头部都已渲染（wrapper 已创建）");
        assertEquals(Boolean.TRUE, rendered[1], "初始收起：高级设置内容网格尚未挂到场景（container 未创建）");
    }
}
