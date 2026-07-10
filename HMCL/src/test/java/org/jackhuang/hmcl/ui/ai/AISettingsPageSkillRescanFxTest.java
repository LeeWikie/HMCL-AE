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

import javafx.scene.Node;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ai.skills.SkillRegistry;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.ComponentSublist;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// 修复"重新扫描技能目录后，刚展开的折叠卡无动画塌缩闪跳"（2026-07-10 真机反馈 4）：
/// 重扫/启停技能现在只就地重建技能列表（AISettingsPage.populateSkillList），tab 里的其余节点
/// （各 ComponentSublist 折叠卡）根本不被重建 —— 展开状态自然保持。
/// 事件注入（fire()）驱动，零 TestFX robot。
public final class AISettingsPageSkillRescanFxTest {

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
        System.setProperty("glass.win.uiScale", "100%");
        System.setProperty("prism.allowhidpi", "false");
        // LineButton → RipplerContainer → AnimationUtils.<clinit> reads SettingsManager.settings();
        // inject an in-memory LauncherSettings the same way GameDirectoriesTest does, so control
        // construction works without a loaded launcher config.
        java.lang.reflect.Field launcherSettings =
                org.jackhuang.hmcl.setting.SettingsManager.class.getDeclaredField("launcherSettings");
        launcherSettings.setAccessible(true);
        if (launcherSettings.get(null) == null) {
            launcherSettings.set(null, new org.jackhuang.hmcl.setting.LauncherSettings());
        }
        FxToolkit.registerPrimaryStage();
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> callable) throws Exception {
        return WaitForAsyncUtils.asyncFx(callable).get(10, TimeUnit.SECONDS);
    }

    private static void writeSkill(Path skillsDir, String name) throws Exception {
        Path dir = skillsDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), String.join("\n",
                "---",
                "name: " + name,
                "description: test skill " + name,
                "---",
                "playbook body"), StandardCharsets.UTF_8);
    }

    private static LineButton rowByTitle(ComponentList list, String title) {
        for (Node node : list.getContent()) {
            if (node instanceof LineButton row && title.equals(row.getTitle())) {
                return row;
            }
        }
        return null;
    }

    @Test
    public void rescanRefreshesRowsInPlaceWithoutRebuildingSiblings() throws Exception {
        Path skillsDir = Files.createTempDirectory("hmcl-skills-fx-");
        writeSkill(skillsDir, "alpha");
        SkillRegistry registry = new SkillRegistry();
        registry.setSkillsDir(skillsDir);
        registry.refresh();

        // A stand-in for the tab: a sibling collapsible card next to the skill list —
        // the thing that used to visibly collapse when the whole tab was rebuilt.
        Object[] built = onFx(() -> {
            ComponentList skillList = new ComponentList();
            AISettingsPage.populateSkillList(skillList, registry, registry::refresh);
            ComponentSublist sibling = new ComponentSublist();
            sibling.setTitle("sibling-card");
            VBox tab = new VBox(skillList, sibling);
            return new Object[]{skillList, sibling, tab};
        });
        ComponentList skillList = (ComponentList) built[0];
        ComponentSublist sibling = (ComponentSublist) built[1];
        VBox tab = (VBox) built[2];

        assertNotNull(onFx(() -> rowByTitle(skillList, "alpha")), "the scanned skill must be listed");
        LineButton rescanRow = onFx(() -> rowByTitle(skillList, i18n("ai.settings.skills.rescan")));
        assertNotNull(rescanRow, "the list must end with the 重新扫描 row");

        // A new skill appears on disk; the user hits 重新扫描.
        writeSkill(skillsDir, "beta");
        onFx(() -> {
            rescanRow.fire();
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertNotNull(onFx(() -> rowByTitle(skillList, "alpha")), "existing skill row must survive the rescan");
        assertNotNull(onFx(() -> rowByTitle(skillList, "beta")), "newly scanned skill must appear after the rescan");
        // The collapse-flicker regression guard: the rescan must have refreshed the SAME
        // ComponentList node in place — its siblings (the collapsible cards whose expanded
        // state used to be lost) are the exact same node instances, untouched.
        Object[] after = onFx(() -> new Object[]{tab.getChildren().get(0), tab.getChildren().get(1)});
        assertSame(skillList, after[0], "the skill list must be refreshed in place, not replaced");
        assertSame(sibling, after[1], "sibling collapsible cards must not be rebuilt by a rescan");
    }

    @Test
    public void togglingASkillAlsoRefreshesInPlace() throws Exception {
        Path skillsDir = Files.createTempDirectory("hmcl-skills-fx-");
        writeSkill(skillsDir, "gamma");
        SkillRegistry registry = new SkillRegistry();
        registry.setSkillsDir(skillsDir);
        registry.refresh();

        ComponentList skillList = onFx(() -> {
            ComponentList list = new ComponentList();
            AISettingsPage.populateSkillList(list, registry, registry::refresh);
            return list;
        });

        LineButton gammaRow = onFx(() -> rowByTitle(skillList, "gamma"));
        assertNotNull(gammaRow);
        assertFalse(registry.isDisabled("gamma"), "precondition: the skill starts enabled");

        onFx(() -> {
            gammaRow.fire();
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(registry.isDisabled("gamma"), "one tap must disable the skill");
        assertNotNull(onFx(() -> rowByTitle(skillList, "gamma")),
                "the row must still be present after the in-place refresh");
    }
}
