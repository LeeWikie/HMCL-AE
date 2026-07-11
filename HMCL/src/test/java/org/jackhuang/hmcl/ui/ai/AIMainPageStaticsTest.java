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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import java.lang.reflect.Method;

import static org.jackhuang.hmcl.ui.ai.AiMainPageFxTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Pure-logic pins for two of the 2026-07-11 composer-polish items:
///  - item 2: {@link AIMainPage#formatTokens(int)} grew an M (百万) tier so a 1,000,000-token context
///    window renders as "1M" instead of the old ugly "1000.0k", with trailing zeros trimmed in both
///    the k and M tiers;
///  - item 1: the pricing/cost-estimation system is 归档 (archived/shelved) — a settled decision, not
///    a temporary hide — so the pricing UI is gated OFF ({@code PRICING_UI_ENABLED == false}) in both
///    AI pages, and the two switches stay in lockstep so a future re-enable (one-flag resurrection)
///    can't half-flip. Underlying pricing code/data is preserved (see {@link AIMainPage#PRICING_UI_ENABLED}).
///
/// The assertions only touch statics, but merely CLASS-LOADING {@link AIMainPage} needs the FX
/// toolkit (its static init sets the platform user-agent stylesheet), so we register the primary
/// stage in `@BeforeAll` — otherwise the first test to force init would poison the class for the
/// whole forked JVM with "Toolkit not initialized" and cascade into every later AI test.
public final class AIMainPageStaticsTest {

    @BeforeAll
    static void setUpToolkit() throws Exception {
        assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "no display — skipping FX UI test");
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

    private static String formatTokens(int n) throws ReflectiveOperationException {
        Method m = AIMainPage.class.getDeclaredMethod("formatTokens", int.class);
        m.setAccessible(true);
        return (String) m.invoke(null, n);
    }

    @Test
    public void formatTokensKeepsSmallAndKTiers() throws Exception {
        assertEquals("0", formatTokens(0));
        assertEquals("500", formatTokens(500));
        assertEquals("999", formatTokens(999));
        assertEquals("1k", formatTokens(1000));           // 1.0 → trimmed to 1
        assertEquals("58.4k", formatTokens(58399));       // 58.399 → %.1f
        assertEquals("128k", formatTokens(128000));       // 128.0 → trimmed
    }

    @Test
    public void formatTokensAddsMillionTier() throws Exception {
        assertEquals("1M", formatTokens(1_000_000));      // was the ugly "1000.0k"
        assertEquals("1.05M", formatTokens(1_050_000));
        assertEquals("1.1M", formatTokens(1_100_000));    // 1.10 → trailing zero trimmed
        assertEquals("1.5M", formatTokens(1_500_000));
        assertEquals("2M", formatTokens(2_000_000));
    }

    @Test
    public void pricingUiIsGatedOffAndTheTwoSwitchesAgree() {
        assertFalse(AIMainPage.PRICING_UI_ENABLED,
                "pricing system is archived/归档 — UI stays fully hidden (2026-07-11 用户决策:定价体系归档)");
        assertEquals(AIMainPage.PRICING_UI_ENABLED, AISettingsPage.PRICING_UI_ENABLED,
                "the model-editor pricing pane switch must track the main-page pricing switch");
    }
}
