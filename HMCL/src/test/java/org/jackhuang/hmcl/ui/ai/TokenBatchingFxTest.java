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

import javafx.scene.control.Label;
import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.AiSessionStore;
import org.jackhuang.hmcl.ai.llm.LlmStreamCallback;
import org.jackhuang.hmcl.ai.llm.LlmUsage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.jackhuang.hmcl.ui.ai.AiMainPageFxTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// P12 (blueprint B1): tokens are batched — many onToken calls coalesce into at most one FX drain
/// per pulse — and batching must lose/reorder NOTHING: after a rapid burst from a background
/// thread the bubble text equals the exact concatenation, and a tool call interleaved mid-stream
/// finalizes the first segment with exactly its own tokens (FIFO ordering argument in
/// createStreamCallback). Drives the REAL production callback via the package-private factory.
public final class TokenBatchingFxTest {

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
    public void rapidTokenBurstRendersCompleteAndInOrder() throws Exception {
        AIMainPage page = showPage();
        AiSessionStore store = (AiSessionStore) getField(page, "sessionStore");
        // A freshly created session, not whatever the constructor happened to auto-create/reuse as
        // "current" — isolates this test from ambient store state (see MessageActionsHoverFxTest).
        AiSession session = store.createSession();
        try {
            int generation = (Integer) getField(page, "responseGeneration");

            StringBuilder fullContent = new StringBuilder();
            StringBuilder segment = new StringBuilder();
            LlmStreamCallback callback = page.createStreamCallback(
                    null, session, generation, fullContent, segment, new AtomicReference<LlmUsage>());

            // ---- burst 1: 500 tokens from a NON-FX thread ----
            StringBuilder expectedFirst = new StringBuilder();
            Thread producer = new Thread(() -> {
                for (int i = 0; i < 500; i++) {
                    callback.onToken("t" + i + " ");
                }
            }, "test-token-producer");
            for (int i = 0; i < 500; i++) {
                expectedFirst.append("t").append(i).append(" ");
            }
            producer.start();
            producer.join(10_000);
            WaitForAsyncUtils.waitForFxEvents();

            Label firstBubble = (Label) getField(page, "streamingBubble");
            assertNotNull(firstBubble, "a streaming bubble must exist after the first tokens");
            assertEquals(expectedFirst.toString(), firstBubble.getText(),
                    "batched rendering must lose and reorder nothing");
            assertEquals(expectedFirst.toString(), segment.toString(),
                    "segment must hold every token of the current segment");
            assertEquals(expectedFirst.toString(), fullContent.toString());

            // ---- interleaved tool call: finalizes segment 1 with EXACTLY its own tokens ----
            Thread interleaver = new Thread(() -> {
                callback.onToolActivity("demo_tool", "{}");
                for (int i = 0; i < 100; i++) {
                    callback.onToken("s" + i + " ");
                }
            }, "test-token-interleaver");
            StringBuilder expectedSecond = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                expectedSecond.append("s").append(i).append(" ");
            }
            interleaver.start();
            interleaver.join(10_000);
            WaitForAsyncUtils.waitForFxEvents();

            assertEquals(expectedFirst.toString(), firstBubble.getText(),
                    "the finalized first segment must contain exactly its own tokens — no bleed "
                            + "from the second segment (FIFO drain-before-finalize ordering)");
            Label secondBubble = (Label) getField(page, "streamingBubble");
            assertNotNull(secondBubble, "text after a tool call starts a NEW segment bubble");
            assertNotSame(firstBubble, secondBubble);
            assertEquals(expectedSecond.toString(), secondBubble.getText());
            assertEquals(expectedFirst + expectedSecond.toString(), fullContent.toString(),
                    "fullContent accumulates the whole turn across segments");
        } finally {
            store.deleteSession(session.getId());
        }
    }

    @Test
    public void reasoningTokensBatchIntoTheLiveCard() throws Exception {
        AIMainPage page = showPage();
        AiSessionStore store = (AiSessionStore) getField(page, "sessionStore");
        // A freshly created session, not whatever the constructor happened to auto-create/reuse as
        // "current" — isolates this test from ambient store state (see MessageActionsHoverFxTest).
        AiSession session = store.createSession();
        try {
            int generation = (Integer) getField(page, "responseGeneration");

            LlmStreamCallback callback = page.createStreamCallback(
                    null, session, generation, new StringBuilder(), new StringBuilder(),
                    new AtomicReference<LlmUsage>());

            StringBuilder expected = new StringBuilder();
            Thread producer = new Thread(() -> {
                for (int i = 0; i < 300; i++) {
                    callback.onReasoningToken("r" + i + " ");
                }
            }, "test-reasoning-producer");
            for (int i = 0; i < 300; i++) {
                expected.append("r").append(i).append(" ");
            }
            producer.start();
            producer.join(10_000);
            WaitForAsyncUtils.waitForFxEvents();

            ReasoningCard card = (ReasoningCard) getField(page, "reasoningLiveCard");
            assertNotNull(card, "reasoning tokens must create the live card");
            Label content = (Label) getField(card, "content");
            assertEquals(expected.toString(), content.getText(),
                    "batched reasoning stream must be complete and in order");
        } finally {
            store.deleteSession(session.getId());
        }
    }
}
