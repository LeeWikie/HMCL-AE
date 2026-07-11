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

import javafx.scene.control.TextArea;
import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.AiSessionStore;
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

/// 2-11 (blueprint B1): the composer draft must follow its session across ALL three switch paths.
/// createSession and switchToSessionAndScroll used to bypass the stash/restore entirely, so text
/// typed in a new session was attributed to the old one (and vice versa). Event/direct-method
/// injection only (A7).
public final class DraftOwnershipFxTest {

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
    public void draftsFollowTheirSessionsAcrossAllSwitchPaths() throws Exception {
        AIMainPage page = showPage();
        AiSessionStore store = (AiSessionStore) getField(page, "sessionStore");
        TextArea input = (TextArea) getField(page, "inputField");
        AiSession sessionA = store.getCurrentSession();
        assertNotNull(sessionA);
        assertEquals(sessionA.getId(), getField(page, "draftSessionId"),
                "constructor must establish the draft-ownership invariant for the initial session");

        // Type a draft in A, then create a new session (path 1: createSession). The freshly
        // created session must NOT stack on an empty A — give A a message first so createSession
        // really switches.
        WaitForAsyncUtils.asyncFx(() -> {
            sessionA.addMessage(new org.jackhuang.hmcl.ai.llm.LlmMessage("user", "占位消息"));
            input.setText("草稿A");
            try {
                invoke(page, "createSession", new Class<?>[0]);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();

        AiSession sessionB = store.getCurrentSession();
        assertNotNull(sessionB);
        assertNotEquals(sessionA.getId(), sessionB.getId(), "createSession must switch sessions");
        assertEquals("", input.getText(),
                "the new session starts with an empty composer — A's draft must not leak in");
        assertEquals(sessionB.getId(), getField(page, "draftSessionId"),
                "composer ownership must move to the new session (bug 7.9)");

        // Type a draft in B, then jump back to A (path 2: switchToSessionAndScroll).
        WaitForAsyncUtils.asyncFx(() -> {
            input.setText("草稿B");
            try {
                invoke(page, "switchToSessionAndScroll",
                        new Class<?>[]{String.class, String.class}, sessionA.getId(), null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("草稿A", input.getText(),
                "switching back must restore A's stashed draft (switchToSessionAndScroll path)");
        assertEquals(sessionA.getId(), getField(page, "draftSessionId"));

        // And back to B (path 3: loadSession — the sidebar click path).
        WaitForAsyncUtils.asyncFx(() -> {
            store.setCurrentSessionId(sessionB.getId());
            try {
                invoke(page, "loadSession", new Class<?>[]{AiSession.class}, sessionB);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(10, TimeUnit.SECONDS);
        // loadSession's message render is debounced (~60ms) — the draft swap itself is synchronous,
        // but wait the debounce out so no stray runLater lands during the next assertion block.
        Thread.sleep(200);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("草稿B", input.getText(),
                "B's draft must come back on the loadSession path");
        assertEquals(sessionB.getId(), getField(page, "draftSessionId"));

        // The stash map still attributes A's draft to A — the original bug filed drafts under
        // whichever session the composer LAST switched through, not the one they were typed in.
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> drafts =
                (java.util.Map<String, String>) getField(page, "sessionDrafts");
        assertEquals("草稿A", drafts.get(sessionA.getId()));
    }
}
