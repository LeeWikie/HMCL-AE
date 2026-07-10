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
package org.jackhuang.hmcl.ui.construct;

import com.jfoenix.controls.JFXToggleButton;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Regression coverage for the "settings toggles cannot be clicked" bug: a click landing ON the
/// JFXToggleButton itself used to flip the switch TWICE — once by the ToggleButton's own mouse
/// handling, once by the bubbled row click that [LineButtonBase] wires to [LineToggleButton#fire] —
/// for a net no-op that made every toggle in the app appear stuck. The fix makes the inner switch
/// mouse-transparent, so the whole row (switch included) is a SINGLE click surface.
///
/// Event-injection only (no TestFX robot): a full PRESSED → RELEASED → CLICKED sequence is
/// dispatched with [Event#fireEvent], on the switch node and on the row, asserting the net effect
/// of one click is exactly ONE flip in both cases.
public final class LineToggleButtonFxTest {

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
        System.setProperty("glass.win.uiScale", "100%");
        System.setProperty("prism.allowhidpi", "false");
        FxToolkit.registerPrimaryStage();
    }

    @AfterAll
    static void tearDownToolkit() throws Exception {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            FxToolkit.cleanupStages();
        }
    }

    private static MouseEvent mouseEvent(javafx.event.EventType<MouseEvent> type) {
        return new MouseEvent(type, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false,
                true, false, false,
                false, false, true, null);
    }

    /// Fires the full PRESSED → RELEASED → CLICKED sequence a real primary-button click delivers
    /// to {@code target}, on the FX thread.
    private static void fireClickSequence(Node target) throws Exception {
        WaitForAsyncUtils.asyncFx(() -> {
            Event.fireEvent(target, mouseEvent(MouseEvent.MOUSE_PRESSED));
            Event.fireEvent(target, mouseEvent(MouseEvent.MOUSE_RELEASED));
            Event.fireEvent(target, mouseEvent(MouseEvent.MOUSE_CLICKED));
        }).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static JFXToggleButton findInnerSwitch(Node root) {
        if (root instanceof JFXToggleButton toggle) {
            return toggle;
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                JFXToggleButton found = findInnerSwitch(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    public void innerSwitchIsMouseTransparentSoTheRowIsTheSingleClickSurface() throws Exception {
        LineToggleButton row = WaitForAsyncUtils.asyncFx(LineToggleButton::new).get(10, TimeUnit.SECONDS);
        JFXToggleButton inner = findInnerSwitch(row);
        assertNotNull(inner, "the LineToggleButton must contain its JFXToggleButton switch");
        assertTrue(inner.isMouseTransparent(),
                "the inner switch must be mouse-transparent — otherwise a click on it flips twice (net no-op)");
    }

    @Test
    public void clickOnTheSwitchItselfFlipsExactlyOnce() throws Exception {
        LineToggleButton row = WaitForAsyncUtils.asyncFx(LineToggleButton::new).get(10, TimeUnit.SECONDS);
        JFXToggleButton inner = findInnerSwitch(row);
        assertNotNull(inner);
        assertFalse(row.isSelected(), "precondition: the toggle starts unselected");

        fireClickSequence(inner);
        assertTrue(row.isSelected(), "one click on the switch itself must flip the toggle exactly once");

        fireClickSequence(inner);
        assertFalse(row.isSelected(), "a second click must flip it back — not get stuck");
    }

    @Test
    public void clickOnTheRowAreaFlipsExactlyOnceAndFiresTheActionEvent() throws Exception {
        LineToggleButton row = WaitForAsyncUtils.asyncFx(LineToggleButton::new).get(10, TimeUnit.SECONDS);
        AtomicBoolean actionFired = new AtomicBoolean();
        WaitForAsyncUtils.asyncFx(() -> row.addEventHandler(javafx.event.ActionEvent.ACTION,
                e -> actionFired.set(true))).get(10, TimeUnit.SECONDS);
        assertFalse(row.isSelected(), "precondition: the toggle starts unselected");

        fireClickSequence(row);
        assertTrue(row.isSelected(), "one click on the row area must flip the toggle exactly once");
        assertTrue(actionFired.get(), "the row click must also fire the LineToggleButton's ActionEvent");

        fireClickSequence(row);
        assertFalse(row.isSelected(), "a second row click must flip it back");
    }
}
