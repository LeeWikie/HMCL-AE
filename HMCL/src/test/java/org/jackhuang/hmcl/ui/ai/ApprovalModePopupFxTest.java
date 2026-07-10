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

import com.jfoenix.controls.JFXPopup;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import org.jackhuang.hmcl.ai.AiApprovalMode;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ui.construct.IconedItem;
import org.jackhuang.hmcl.ui.construct.PopupMenu;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.TimeUnit;

import static org.jackhuang.hmcl.ui.ai.AiMainPageFxTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Covers the composer's approval-mode pill/popup after {@link AiApprovalMode} was restored from a
/// single fixed `Auto` mode back to a three-way pick (Auto / Ask / yolo — see that enum's own doc
/// for the SAFE/ASK/YOLO merge-then-restore history). Drives the REAL popup {@code
/// showApprovalModePopup()} builds (reached via {@link JFXPopup#getPopupContent()}, which returns
/// the exact {@link PopupMenu} instance passed to the popup's constructor) and fires genuine
/// {@code MOUSE_CLICKED} events at its {@link IconedItem} rows (event injection, no physical robot —
/// same technique as {@code CollapseHeaderFxTest}), so this exercises the same code path a real
/// click would: all three rows must be listed and clickable, picking one must persist the mode to
/// {@link AiSettings} and refresh the pill's text, the checked row must always track the
/// currently-persisted mode, and — the one detail explicitly called out by the restore — the yolo
/// row/pill text must render as the literal lowercase string `"yolo"`, never `"Yolo"`/`"YOLO"`.
public final class ApprovalModePopupFxTest {

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
        System.setProperty("glass.win.uiScale", "100%");
        System.setProperty("prism.allowhidpi", "false");
        FxToolkit.registerPrimaryStage();
        ensureSettingsManagerLoaded();
        prepareFirstUseMarkers();
    }

    @AfterAll
    static void tearDownToolkit() throws Exception {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            FxToolkit.cleanupStages();
        }
    }

    @BeforeEach
    void discardExceptionsLeakedByUnrelatedEarlierTests() {
        WaitForAsyncUtils.clearExceptions();
    }

    @Test
    public void allThreeModesAreListedAndClickablePersistingToAiSettings() throws Exception {
        AIMainPage page = showPage();
        AiSettings aiSettings = (AiSettings) getField(page, "aiSettings");
        Label badge = (Label) getField(page, "approvalBadge");

        PopupMenu menu = openPopup(page);
        assertEquals(3, menu.getContent().size(), "the popup must list exactly the three approval modes");

        // Auto (default) -> Ask.
        clickRow(menu, "Ask");
        assertEquals(AiApprovalMode.ASK, AiApprovalMode.fromId(aiSettings.getApprovalMode()),
                "clicking the Ask row must persist AiApprovalMode.ASK to AiSettings");
        assertEquals("Ask", badge.getText(), "the pill text must refresh to the newly-picked Ask mode");

        // Ask -> yolo: the row/pill text must be the literal LOWERCASE string, not "Yolo"/"YOLO".
        menu = openPopup(page);
        clickRow(menu, "yolo");
        assertEquals(AiApprovalMode.YOLO, AiApprovalMode.fromId(aiSettings.getApprovalMode()),
                "clicking the yolo row must persist AiApprovalMode.YOLO to AiSettings");
        assertEquals("yolo", badge.getText(),
                "the yolo pill text must be the literal lowercase string \"yolo\", never \"Yolo\"/\"YOLO\"");

        // yolo -> Auto: round-trips back to the default.
        menu = openPopup(page);
        clickRow(menu, "Auto");
        assertEquals(AiApprovalMode.AUTO, AiApprovalMode.fromId(aiSettings.getApprovalMode()),
                "clicking the Auto row must persist AiApprovalMode.AUTO to AiSettings");
        assertEquals("Auto", badge.getText(), "the pill text must refresh back to Auto");
    }

    @Test
    public void checkedRowAlwaysMarksTheCurrentlyPersistedMode() throws Exception {
        AIMainPage page = showPage();

        PopupMenu menu = openPopup(page);
        clickRow(menu, "Ask"); // switch away from the default Auto

        // Re-open after the switch: exactly the Ask row must carry the check icon now.
        menu = openPopup(page);
        for (Node n : menu.getContent()) {
            IconedItem item = (IconedItem) n;
            boolean expectChecked = "Ask".equals(item.getLabel().getText());
            assertEquals(expectChecked, hasCheckIcon(item),
                    "row \"" + item.getLabel().getText() + "\" checked-state mismatch (expected checked="
                            + expectChecked + ") after switching to Ask");
        }
    }

    @Test
    public void secondClickOnThePillClosesInsteadOfStackingAnotherPopup() throws Exception {
        AIMainPage page = showPage();

        invokeFx(page, "showApprovalModePopup");
        JFXPopup popup = (JFXPopup) getField(page, "approvalModePopup");
        assertNotNull(popup, "clicking the pill must build/open its popup");
        assertTrue(popup.isShowing(), "the approval-mode popup must be showing after one click");

        invokeFx(page, "showApprovalModePopup");
        assertFalse(popup.isShowing(),
                "a second click on the pill must close the popup instead of stacking another "
                        + "(mirrors the thinking/model/context popups' toggle behaviour)");
    }

    /// Opens the approval-mode popup by invoking the private {@code showApprovalModePopup()}
    /// directly (the pill is a plain {@link Label} wired via {@code FXUtils.onClicked}, not a
    /// fireable button) and returns the live {@link PopupMenu} it built, reached through {@link
    /// JFXPopup#getPopupContent()} — the exact {@code Region} passed to the popup's constructor.
    private static PopupMenu openPopup(AIMainPage page) throws Exception {
        invokeFx(page, "showApprovalModePopup");
        JFXPopup popup = (JFXPopup) getField(page, "approvalModePopup");
        assertNotNull(popup, "clicking the pill must build/open its popup");
        assertTrue(popup.isShowing(), "the approval-mode popup must be showing after one click");
        return (PopupMenu) popup.getPopupContent();
    }

    /// Fires a genuine primary-button {@code MOUSE_CLICKED} at the row whose label matches {@code
    /// expectedLabel} (event injection, no physical robot — same technique as
    /// {@code CollapseHeaderFxTest}'s whole-row click coverage).
    private static void clickRow(PopupMenu menu, String expectedLabel) throws Exception {
        Node row = menu.getContent().stream()
                .filter(n -> n instanceof IconedItem item && expectedLabel.equals(item.getLabel().getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no popup row found with label \"" + expectedLabel + "\" (rows: "
                                + menu.getContent().stream()
                                        .map(n -> n instanceof IconedItem item ? item.getLabel().getText() : n.toString())
                                        .toList()
                                + ")"));
        WaitForAsyncUtils.asyncFx(() -> Event.fireEvent(row, new MouseEvent(
                MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null)
        )).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /// Whether an {@link IconedItem} row was built with the checked (`SVG.CHECK`) icon: {@code
    /// IconedItem}'s HBox only gains a leading icon child when one was passed (see its {@code
    /// createHBox}), so a 2-child container means the row is checked; a lone label means it is not.
    private static boolean hasCheckIcon(IconedItem item) {
        return item.getContainer() instanceof HBox hbox && hbox.getChildren().size() > 1;
    }
}
