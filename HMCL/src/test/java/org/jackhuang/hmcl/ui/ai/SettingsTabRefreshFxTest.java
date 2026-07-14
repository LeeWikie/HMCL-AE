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

import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.ai.AiModelDiscoveryService;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.mcp.AiMcpServerConfig;
import org.jackhuang.hmcl.ai.kb.AiKbConfig;
import org.jackhuang.hmcl.ai.ocr.AiOcrConfig;
import org.jackhuang.hmcl.ai.search.AiSearchConfig;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.ComponentSublist;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.TabControl;
import org.jackhuang.hmcl.ui.construct.TabHeader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Regression test for the settings page's "fake refresh" fix (blueprint A1 / bugfix P4):
/// `TabHeader.Tab` builds its content node exactly once, so the old `tab.select(x, false)`
/// calls after a data change never rebuilt anything — the UI silently kept showing stale
/// content. The fix routes those refresh points through `invalidateTab` (null the node, then
/// re-select so the supplier runs again). Event injection only (`Event.fireEvent`/direct
/// method), no physical robot — see ReasoningCardFxTest for the pipeline rationale.
public final class SettingsTabRefreshFxTest {

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
        System.setProperty("glass.win.uiScale", "100%");
        System.setProperty("prism.allowhidpi", "false");
        FxToolkit.registerPrimaryStage();
        // This class builds its own AISettingsPage(new AiSettings(tempConfigDir)) rather than a
        // shared AIMainPage, so it doesn't need AiMainPageFxTestSupport.ensureSettingsManagerLoaded
        // / prepareFirstUseMarkers — but the skill-rescan and MCP-edit tests below still read/write
        // SettingsManager.localConfigDirectory() directly (ai-skills/, ai-mcp-settings.json), so it
        // still needs the same disposable-directory isolation as every AIMainPage-backed test.
        AiMainPageFxTestSupport.useIsolatedConfigDirectory();
        ensureSettingsManagerLoaded();
    }

    @AfterAll
    static void tearDownToolkit() throws Exception {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            FxToolkit.cleanupStages();
        }
        AiMainPageFxTestSupport.restoreRealConfigDirectory();
    }

    @BeforeEach
    void discardExceptionsLeakedByUnrelatedEarlierTests() {
        // See JsonEditorDialogPaneFxTest: TestFX re-throws FX-thread exceptions queued by
        // EARLIER test classes from the next waitForFxEvents() call; clear them so only this
        // test's own exceptions can fail it.
        WaitForAsyncUtils.clearExceptions();
    }

    /// Seeds SettingsManager.launcherSettings so lazily-animated containers (TransitionPane)
    /// don't throw "Configuration hasn't been loaded" — same technique as
    /// JsonEditorDialogPaneFxTest#ensureSettingsManagerLoaded.
    private static void ensureSettingsManagerLoaded() throws ReflectiveOperationException {
        Field field = SettingsManager.class.getDeclaredField("launcherSettings");
        field.setAccessible(true);
        if (field.get(null) == null) {
            field.set(null, new LauncherSettings());
        }
    }

    // ---- shared scaffolding -------------------------------------------------------------

    private AISettingsPage page;
    private AiSearchConfig injectedSearchConfig;
    private AiOcrConfig injectedOcrConfig;

    private void showPage() throws Exception {
        AtomicReference<AISettingsPage> ref = new AtomicReference<>();
        Path tempConfigDir = Files.createTempDirectory("hmcl-ae-settings-fxtest");
        injectedSearchConfig = new AiSearchConfig();
        injectedOcrConfig = new AiOcrConfig();
        FxToolkit.setupSceneRoot(() -> {
            AISettingsPage p = new AISettingsPage(
                    new AiSettings(tempConfigDir),
                    new AiModelDiscoveryService(),
                    () -> {
                    },
                    injectedSearchConfig,
                    injectedOcrConfig,
                    new AiKbConfig());
            ref.set(p);
            StackPane root = new StackPane(p);
            root.setPrefSize(1000, 700);
            return root;
        });
        FxToolkit.showStage();
        WaitForAsyncUtils.waitForFxEvents();
        page = ref.get();
    }

    @SuppressWarnings("unchecked")
    private TabControl.Tab<Node> tabField(String name) throws ReflectiveOperationException {
        Field f = AISettingsPage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (TabControl.Tab<Node>) f.get(page);
    }

    private TabHeader tabHeader() throws ReflectiveOperationException {
        Field f = AISettingsPage.class.getDeclaredField("tab");
        f.setAccessible(true);
        return (TabHeader) f.get(page);
    }

    private void selectTab(TabControl.Tab<Node> t) throws Exception {
        TabHeader header = tabHeader();
        WaitForAsyncUtils.asyncFx(() -> header.select(t, false)).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /// Finds a LineButton by exact title anywhere under {@code root}, descending through both
    /// scene-graph children AND the content lists of ComponentList/ComponentSublist (whose items
    /// only become scene children once the control's skin is created).
    private static LineButton findLineButton(Node root, String title) {
        if (root instanceof LineButton btn && title.equals(btn.getTitle())) {
            return btn;
        }
        if (root instanceof ComponentList list) {
            for (Node child : list.getContent()) {
                LineButton found = findLineButton(child, title);
                if (found != null) return found;
            }
        }
        if (root instanceof ComponentSublist sublist) {
            for (Node child : sublist.getContent()) {
                LineButton found = findLineButton(child, title);
                if (found != null) return found;
            }
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                LineButton found = findLineButton(child, title);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    // ---- tests --------------------------------------------------------------------------

    /// 2026-07-10 真机反馈更新：重扫不再重建整个 tab（旧行为让刚展开的 ComponentSublist 无动画
    /// 塌缩闪跳），而是就地刷新技能列表内容（AISettingsPage.populateSkillList）。本用例随契约
    /// 更新：tab 节点必须保持同一实例，同时新技能仍要立即可见。
    @Test
    public void rescanRefreshesSkillListInPlaceAndShowsTheNewSkill() throws Exception {
        showPage();
        // P5 contract while we have a page at hand: the settings page must hold THE injected
        // search/OCR config instances (shared with the chat page's tools), not its own copies.
        Field searchField = AISettingsPage.class.getDeclaredField("searchConfig");
        searchField.setAccessible(true);
        assertSame(injectedSearchConfig, searchField.get(page),
                "settings page must share the injected AiSearchConfig instance");
        Field ocrField = AISettingsPage.class.getDeclaredField("ocrConfig");
        ocrField.setAccessible(true);
        assertSame(injectedOcrConfig, ocrField.get(page),
                "settings page must share the injected AiOcrConfig instance");

        String skillName = "zz-fxtest-refresh-skill";
        Path skillsDir = SettingsManager.localConfigDirectory().resolve("ai-skills");
        Path skillDir = skillsDir.resolve(skillName);
        deleteRecursively(skillDir); // stale leftovers from an aborted earlier run
        try {
            TabControl.Tab<Node> skillsTab = tabField("skillsTab");
            selectTab(skillsTab);
            Node before = skillsTab.getNode();
            assertNotNull(before, "selecting the tab must have built its node");
            assertNull(findLineButton(before, skillName),
                    "the fake skill must not exist before it is written to disk");

            // Drop a minimal valid user skill into the scanned directory...
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"),
                    "---\nname: " + skillName + "\ndescription: FX refresh-test fixture\n---\nbody\n",
                    StandardCharsets.UTF_8);

            // ...and fire the "重新扫描技能目录" row. The rescan must make the new skill visible
            // WITHOUT rebuilding the tab node (an in-place list refresh) — rebuilding is exactly
            // what used to collapse the user's expanded sublists with a jarring flicker.
            LineButton reload = findLineButton(before, "重新扫描技能目录");
            assertNotNull(reload, "rescan row must exist in the skills tab");
            WaitForAsyncUtils.asyncFx(() -> Event.fireEvent(reload, new ActionEvent(reload, reload)))
                    .get(10, TimeUnit.SECONDS);
            WaitForAsyncUtils.waitForFxEvents();

            Node after = skillsTab.getNode();
            assertSame(before, after,
                    "rescan must refresh the skill list in place — rebuilding the tab node collapses expanded sublists");
            assertNotNull(findLineButton(after, skillName),
                    "the refreshed skills tab must list the newly scanned skill");
        } finally {
            deleteRecursively(skillDir);
        }
    }

    @Test
    public void mcpEditAcceptRebuildsTheMcpTabNode() throws Exception {
        showPage();
        Path mcpConfigFile = SettingsManager.localConfigDirectory().resolve("ai-mcp-settings.json");
        byte[] previous = Files.exists(mcpConfigFile) ? Files.readAllBytes(mcpConfigFile) : null;
        try {
            TabControl.Tab<Node> mcpTab = tabField("mcpTab");
            selectTab(mcpTab);
            Node before = mcpTab.getNode();
            assertNotNull(before);

            // Drive the exact accept path the JSON editor dialog's resolve branch runs
            // (validate → applyMcpServerEdit); A7 allows direct component-method injection.
            AiMcpServerConfig server = new AiMcpServerConfig();
            server.setDisplayName("zz-fxtest-mcp-server");
            String json = McpServerJsonCodec.toJson(server);
            assertNull(McpServerJsonCodec.validate(json), "round-tripped JSON must validate clean");
            WaitForAsyncUtils.asyncFx(() -> page.applyMcpServerEdit(server, json))
                    .get(10, TimeUnit.SECONDS);
            WaitForAsyncUtils.waitForFxEvents();

            Node after = mcpTab.getNode();
            assertNotSame(before, after, "accepting an MCP edit must rebuild the MCP tab node");
            assertNotNull(findLineButton(after, "zz-fxtest-mcp-server"),
                    "the rebuilt MCP tab must list the newly added server");
        } finally {
            // applyMcpServerEdit persisted the fake server — restore the on-disk state.
            if (previous != null) {
                Files.write(mcpConfigFile, previous);
            } else {
                Files.deleteIfExists(mcpConfigFile);
            }
        }
    }
}
