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

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// FX test for [AiSearchDialog] (blueprint task B5 / CP §1): the cross-session search moved
/// from a hand-built overlay to a native DialogPane. Fake session data is injected through
/// the `Supplier` constructor parameter — no store, no disk. All interaction is event
/// injection (`setText` / `Event.fireEvent`), never a physical robot (A7 discipline).
///
/// Covered: typing populates `.ai-list-row` result rows (message match + title-only match,
/// one per session); DOWN/UP wrap the selection and move `.ai-list-row-selected`; ENTER
/// fires the select callback with the highlighted session and closes; ESC closes without
/// selecting; a no-hit query shows the status row instead of rows.
public final class AiSearchDialogFxTest {

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
        // FXUtils.smoothScrolling reaches AnimationUtils → SettingsManager; seed before FX use
        // (same reflection technique as CollapseHeaderFxTest / JsonEditorDialogPaneFxTest).
        ensureSettingsManagerLoaded();
        FxToolkit.registerPrimaryStage();
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

    private static void ensureSettingsManagerLoaded() throws ReflectiveOperationException {
        Field field = SettingsManager.class.getDeclaredField("launcherSettings");
        field.setAccessible(true);
        if (field.get(null) == null) {
            LauncherSettings settings = new LauncherSettings();
            settings.animationDisabledProperty().set(true);
            field.set(null, settings);
        }
    }

    private static AiSession session(String title, String... messages) {
        AiSession s = new AiSession();
        s.setTitle(title);
        for (String m : messages) {
            s.addMessage(new LlmMessage("user", m));
        }
        return s;
    }

    /// Three sessions: one matching "光影" in a message, one matching only in its title,
    /// one matching nothing — the canonical fixture for a two-row result list.
    private static List<AiSession> fixtureSessions() {
        List<AiSession> sessions = new ArrayList<>();
        sessions.add(session("装模组", "怎么安装光影包？", "先装 OptiFine 或 Iris"));
        sessions.add(session("光影推荐"));
        sessions.add(session("崩溃分析", "游戏启动崩溃了"));
        return sessions;
    }

    private record Shown(AiSearchDialog dialog,
                         AtomicReference<String> selectedSessionId,
                         AtomicReference<String> selectedLine,
                         AtomicInteger closeEvents) {
    }

    private static Shown showDialog(List<AiSession> sessions) throws Exception {
        AtomicReference<AiSearchDialog> ref = new AtomicReference<>();
        AtomicReference<String> selectedSessionId = new AtomicReference<>();
        AtomicReference<String> selectedLine = new AtomicReference<>();
        AtomicInteger closeEvents = new AtomicInteger();
        FxToolkit.setupSceneRoot(() -> {
            AiSearchDialog dialog = new AiSearchDialog(
                    () -> sessions,
                    (sessionId, line) -> {
                        selectedSessionId.set(sessionId);
                        selectedLine.set(line);
                    },
                    null);
            // The decorator normally consumes DialogCloseEvent; count it here instead.
            dialog.addEventHandler(DialogCloseEvent.CLOSE, e -> closeEvents.incrementAndGet());
            ref.set(dialog);
            StackPane root = new StackPane(dialog);
            root.setPrefSize(700, 600);
            return root;
        });
        FxToolkit.showStage();
        WaitForAsyncUtils.waitForFxEvents();
        return new Shown(ref.get(), selectedSessionId, selectedLine, closeEvents);
    }

    private static void setQuery(AiSearchDialog dialog, String query) throws Exception {
        WaitForAsyncUtils.asyncFx(() -> dialog.getSearchField().setText(query)).get(5, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static void pressKey(AiSearchDialog dialog, KeyCode code) throws Exception {
        WaitForAsyncUtils.asyncFx(() -> Event.fireEvent(dialog.getSearchField(),
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)
        )).get(5, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    public void typedQueryPopulatesListRows() throws Exception {
        Shown shown = showDialog(fixtureSessions());
        AiSearchDialog dialog = shown.dialog();

        // Blank query: empty hint visible, no rows, no status.
        assertTrue(dialog.getEmptyLabel().isVisible(), "empty hint shows before any query");
        assertEquals(0, dialog.getResultsBox().getChildren().size());

        setQuery(dialog, "光影");

        // One row per matching session: message match + title-only match; the miss adds none.
        List<Node> rows = dialog.getResultsBox().getChildren();
        assertEquals(2, rows.size(), "message match and title-only match each produce one row");
        for (Node row : rows) {
            assertTrue(row.getStyleClass().contains("ai-list-row"),
                    "result rows wear the shared .ai-list-row family class");
            assertFalse(row.getStyleClass().contains("ai-list-row-selected"),
                    "nothing selected before keyboard navigation");
        }
        assertFalse(dialog.getEmptyLabel().isVisible(), "empty hint hides once a query runs");
        assertFalse(dialog.getStatusLabel().isVisible(), "status row hidden when there are hits");

        // Clearing the query resets to the empty state.
        setQuery(dialog, "");
        assertEquals(0, dialog.getResultsBox().getChildren().size());
        assertTrue(dialog.getEmptyLabel().isVisible());
    }

    @Test
    public void noResultQueryShowsStatusRow() throws Exception {
        Shown shown = showDialog(fixtureSessions());
        AiSearchDialog dialog = shown.dialog();

        setQuery(dialog, "根本不存在的词zzz");

        assertEquals(0, dialog.getResultsBox().getChildren().size());
        assertTrue(dialog.getStatusLabel().isVisible(), "no-hit query surfaces the status row");
        assertFalse(dialog.getStatusLabel().getText().isEmpty());

        // ENTER with nothing selectable stays a no-op: no callback, no close.
        pressKey(dialog, KeyCode.ENTER);
        assertNull(shown.selectedSessionId().get());
        assertEquals(0, shown.closeEvents().get());
    }

    @Test
    public void arrowNavigationWrapsAndEnterSelects() throws Exception {
        List<AiSession> sessions = fixtureSessions();
        Shown shown = showDialog(sessions);
        AiSearchDialog dialog = shown.dialog();

        setQuery(dialog, "光影");
        List<Node> rows = dialog.getResultsBox().getChildren();
        assertEquals(2, rows.size());

        // DOWN: -1 → 0.
        pressKey(dialog, KeyCode.DOWN);
        assertEquals(0, dialog.getSelectedIndex());
        assertTrue(rows.get(0).getStyleClass().contains("ai-list-row-selected"));
        assertFalse(rows.get(1).getStyleClass().contains("ai-list-row-selected"));

        // DOWN: 0 → 1; DOWN again wraps 1 → 0; UP wraps 0 → 1.
        pressKey(dialog, KeyCode.DOWN);
        assertEquals(1, dialog.getSelectedIndex());
        assertTrue(rows.get(1).getStyleClass().contains("ai-list-row-selected"));
        assertFalse(rows.get(0).getStyleClass().contains("ai-list-row-selected"));
        pressKey(dialog, KeyCode.DOWN);
        assertEquals(0, dialog.getSelectedIndex());
        pressKey(dialog, KeyCode.UP);
        assertEquals(1, dialog.getSelectedIndex());

        // ENTER: select callback carries the highlighted session (row 1 = title-only match,
        // whose matching line is the title itself), then the dialog closes itself.
        pressKey(dialog, KeyCode.ENTER);
        assertEquals(sessions.get(1).getId(), shown.selectedSessionId().get(),
                "ENTER selects the highlighted result");
        assertEquals("光影推荐", shown.selectedLine().get(), "title-only match carries the title as line");
        assertEquals(1, shown.closeEvents().get(), "selecting fires exactly one DialogCloseEvent");
    }

    @Test
    public void escapeClosesWithoutSelecting() throws Exception {
        Shown shown = showDialog(fixtureSessions());
        AiSearchDialog dialog = shown.dialog();

        setQuery(dialog, "光影");
        pressKey(dialog, KeyCode.ESCAPE);

        assertEquals(1, shown.closeEvents().get(), "ESC in the field fires exactly one close event");
        assertNull(shown.selectedSessionId().get(), "ESC never selects");

        // onDialogClosed (invoked by the decorator when the dialog actually leaves) runs the
        // injected close callback — the double-open guard reset path in AIMainPage.
        AtomicInteger closedRuns = new AtomicInteger();
        AiSearchDialog withGuard = WaitForAsyncUtils.asyncFx(() ->
                new AiSearchDialog(ArrayList::new, (a, b) -> {
                }, closedRuns::incrementAndGet)).get(5, TimeUnit.SECONDS);
        withGuard.onDialogClosed();
        assertEquals(1, closedRuns.get(), "onDialogClosed forwards to the injected callback");
    }
}
