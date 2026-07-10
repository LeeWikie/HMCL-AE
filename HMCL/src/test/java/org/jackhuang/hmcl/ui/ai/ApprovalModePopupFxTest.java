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

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.jackhuang.hmcl.ui.ai.AiMainPageFxTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Covers the composer's mode pill/popup after it grew a fourth, Claude-Code-mirroring `Plan` row
/// alongside the restored {@link AiApprovalMode} three-way pick (Auto / Manual / yolo — see that
/// enum's own doc for the SAFE/ASK/YOLO merge-then-restore-then-rename history; `Manual` was named
/// `Ask` before a pure rename pass). Plan Mode itself ({@code AIMainPage#planMode}, normally toggled
/// by `/plan`) is a SEPARATE boolean, deliberately NOT folded into the {@link AiApprovalMode} enum —
/// the popup only merges the two orthogonal states into one single-choice PRESENTATION (see
/// {@code AIMainPage#showApprovalModePopup}'s own doc). Drives the REAL popup {@code
/// showApprovalModePopup()} builds (reached via {@link JFXPopup#getPopupContent()}, which returns
/// the exact {@link PopupMenu} instance passed to the popup's constructor) and fires genuine
/// {@code MOUSE_CLICKED} events at its {@link IconedItem} rows (event injection, no physical robot —
/// same technique as {@code CollapseHeaderFxTest}), so this exercises the same code path a real
/// click would: all four rows must be listed in the fixed Manual/Plan/Auto/yolo order and clickable,
/// picking Manual/Auto/yolo must persist the mode to {@link AiSettings} AND turn Plan Mode off,
/// picking Plan must flip Plan Mode on WITHOUT touching the persisted mode, the checked row must
/// always track "Plan Mode if active, else the persisted mode" (never both at once), and — the one
/// detail explicitly called out by the restore — the yolo row/pill text must render as the literal
/// lowercase string `"yolo"`, never `"Yolo"`/`"YOLO"`.
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
    public void fourRowsAreListedInFixedManualPlanAutoYoloOrder() throws Exception {
        AIMainPage page = showPage();

        PopupMenu menu = openPopup(page);
        assertEquals(4, menu.getContent().size(), "the popup must list exactly Manual/Plan/Auto/yolo");

        List<String> labels = menu.getContent().stream()
                .map(n -> ((IconedItem) n).getLabel().getText())
                .toList();
        assertEquals(List.of("Manual", "Plan", "Auto", "yolo"), labels,
                "rows must appear in the fixed Manual/Plan/Auto/yolo order (mirrors Claude Code's own "
                        + "Mode picker's row order)");
    }

    @Test
    public void clickingManualAutoYoloPersistsModeAndRefreshesPill() throws Exception {
        AIMainPage page = showPage();
        AiSettings aiSettings = (AiSettings) getField(page, "aiSettings");
        Label badge = (Label) getField(page, "approvalBadge");

        // Auto (default) -> Manual.
        PopupMenu menu = openPopup(page);
        clickRow(menu, "Manual");
        assertEquals(AiApprovalMode.MANUAL, AiApprovalMode.fromId(aiSettings.getApprovalMode()),
                "clicking the Manual row must persist AiApprovalMode.MANUAL to AiSettings");
        assertEquals("Manual", badge.getText(), "the pill text must refresh to the newly-picked Manual mode");

        // Manual -> yolo: the row/pill text must be the literal LOWERCASE string, not "Yolo"/"YOLO".
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
    public void clickingPlanActivatesItWithoutTouchingThePersistedApprovalMode() throws Exception {
        AIMainPage page = showPage();
        AiSettings aiSettings = (AiSettings) getField(page, "aiSettings");
        Label badge = (Label) getField(page, "approvalBadge");

        // Switch off the Auto default first so we can tell "untouched" apart from "coincidentally
        // still the default".
        PopupMenu menu = openPopup(page);
        clickRow(menu, "Manual");
        assertEquals(AiApprovalMode.MANUAL, AiApprovalMode.fromId(aiSettings.getApprovalMode()));

        menu = openPopup(page);
        clickRow(menu, "Plan");
        assertEquals(Boolean.TRUE, getField(page, "planMode"), "clicking Plan must flip Plan Mode on");
        assertEquals(AiApprovalMode.MANUAL, AiApprovalMode.fromId(aiSettings.getApprovalMode()),
                "picking Plan must NOT touch the persisted AiApprovalMode — Manual stays parked "
                        + "underneath for when Plan Mode is turned back off");
        assertEquals("Plan", badge.getText(),
                "the pill must show \"Plan\" while Plan Mode is active, regardless of the parked mode");

        // Leaving Plan by picking Manual again (the SAME mode that was already parked) must still
        // refresh the pill away from "Plan" even though the persisted mode string doesn't change —
        // setApprovalMode's persistence no-op must not suppress the badge refresh.
        menu = openPopup(page);
        clickRow(menu, "Manual");
        assertEquals(Boolean.FALSE, getField(page, "planMode"), "picking Manual again must turn Plan Mode back off");
        assertEquals("Manual", badge.getText(), "the pill must go back to showing the parked Manual mode");
    }

    @Test
    public void checkedRowIsPlanWheneverPlanModeIsActiveRegardlessOfPersistedMode() throws Exception {
        AIMainPage page = showPage();

        PopupMenu menu = openPopup(page);
        clickRow(menu, "yolo"); // switch away from the default Auto, parked mode = yolo

        menu = openPopup(page);
        clickRow(menu, "Plan"); // Plan Mode on; yolo stays parked underneath

        // Re-open after activating Plan: exactly the Plan row must carry the check icon now, NOT
        // the yolo row the persisted AiApprovalMode still resolves to.
        menu = openPopup(page);
        for (Node n : menu.getContent()) {
            IconedItem item = (IconedItem) n;
            boolean expectChecked = "Plan".equals(item.getLabel().getText());
            assertEquals(expectChecked, hasCheckIcon(item),
                    "row \"" + item.getLabel().getText() + "\" checked-state mismatch (expected checked="
                            + expectChecked + ") while Plan Mode is active");
        }
    }

    @Test
    public void checkedRowAlwaysMarksTheCurrentlyPersistedModeWhenPlanIsInactive() throws Exception {
        AIMainPage page = showPage();

        PopupMenu menu = openPopup(page);
        clickRow(menu, "Manual"); // switch away from the default Auto

        // Re-open after the switch: exactly the Manual row must carry the check icon now.
        menu = openPopup(page);
        for (Node n : menu.getContent()) {
            IconedItem item = (IconedItem) n;
            boolean expectChecked = "Manual".equals(item.getLabel().getText());
            assertEquals(expectChecked, hasCheckIcon(item),
                    "row \"" + item.getLabel().getText() + "\" checked-state mismatch (expected checked="
                            + expectChecked + ") after switching to Manual");
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
