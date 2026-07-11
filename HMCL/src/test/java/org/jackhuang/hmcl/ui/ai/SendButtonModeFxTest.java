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

import com.jfoenix.controls.JFXButton;
import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.AiSessionStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.jackhuang.hmcl.ui.ai.AiMainPageFxTestSupport.*;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// B3 send/stop button (CP §7): while the VISIBLE session streams, the raised send button flips
/// to "停止" and gains the `ai-stop-btn` danger class (now a real `.jfx-button-raised.ai-stop-btn`
/// rule, C-08 — it used to be a ghost class with zero visual difference); once idle it flips
/// back. Event/direct-method injection only (A7).
public final class SendButtonModeFxTest {

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
        System.setProperty("glass.win.uiScale", "100%");
        System.setProperty("prism.allowhidpi", "false");
        FxToolkit.registerPrimaryStage();
        useIsolatedConfigDirectory();
        ensureSettingsManagerLoaded();
        prepareFirstUseMarkers();
    }

    @AfterAll
    static void tearDownToolkit() throws Exception {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            FxToolkit.cleanupStages();
        }
        restoreRealConfigDirectory();
    }

    @BeforeEach
    void discardExceptionsLeakedByUnrelatedEarlierTests() {
        WaitForAsyncUtils.clearExceptions();
    }

    @Test
    public void sendButtonFlipsBetweenSendAndStopModes() throws Exception {
        AIMainPage page = showPage();
        AiSessionStore store = (AiSessionStore) getField(page, "sessionStore");
        // A freshly created session, not whatever the constructor happened to auto-create/reuse as
        // "current" — isolates this test's session id from ambient store state (see
        // MessageActionsHoverFxTest).
        AiSession session = store.createSession();
        try {
            JFXButton sendBtn = (JFXButton) getField(page, "sendBtn");

            // Streaming the CURRENT session → Stop mode with the danger class.
            WaitForAsyncUtils.asyncFx(() -> {
                try {
                    setField(page, "streamSessionId", session.getId());
                    setField(page, "currentResponse", new CompletableFuture<Void>());
                    invoke(page, "updateSendButtonMode", new Class<?>[0]);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).get(10, TimeUnit.SECONDS);
            WaitForAsyncUtils.waitForFxEvents();

            assertEquals(i18n("ai.stop"), sendBtn.getText());
            assertTrue(sendBtn.getStyleClass().contains("ai-stop-btn"),
                    "stop mode must carry the ai-stop-btn class (danger colours + behaviour hook)");
            assertTrue(sendBtn.getStyleClass().contains("jfx-button-raised"),
                    "the button stays a native raised button in both modes");

            // Idle again → Send mode, class removed.
            WaitForAsyncUtils.asyncFx(() -> {
                try {
                    setField(page, "streamSessionId", null);
                    setField(page, "currentResponse", null);
                    invoke(page, "updateSendButtonMode", new Class<?>[0]);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).get(10, TimeUnit.SECONDS);
            WaitForAsyncUtils.waitForFxEvents();

            assertEquals(i18n("ai.send"), sendBtn.getText());
            assertFalse(sendBtn.getStyleClass().contains("ai-stop-btn"),
                    "idle mode must drop the ai-stop-btn class");
        } finally {
            store.deleteSession(session.getId());
        }
    }
}
