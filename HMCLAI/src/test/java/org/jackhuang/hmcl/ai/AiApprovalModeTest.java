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
package org.jackhuang.hmcl.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Unit tests for {@link AiApprovalMode}: covers the restored three-way `auto`/`manual`/`yolo` id
/// space, the legacy `"safe"` AND `"ask"` compatibility aliases (`"safe"` from before the original
/// SAFE/ASK/YOLO merge, `"ask"` from before the later `ASK` &rarr; `MANUAL` pure-rename pass — see
/// that enum's own doc for the full history), and the deliberately-lowercase `yolo` display name.
public final class AiApprovalModeTest {

    @Test
    void fromIdResolvesTheThreeCurrentIds() {
        assertEquals(AiApprovalMode.AUTO, AiApprovalMode.fromId("auto"));
        assertEquals(AiApprovalMode.MANUAL, AiApprovalMode.fromId("manual"));
        assertEquals(AiApprovalMode.YOLO, AiApprovalMode.fromId("yolo"));
    }

    @Test
    void fromIdIsCaseInsensitive() {
        assertEquals(AiApprovalMode.AUTO, AiApprovalMode.fromId("AUTO"));
        assertEquals(AiApprovalMode.MANUAL, AiApprovalMode.fromId("Manual"));
        assertEquals(AiApprovalMode.YOLO, AiApprovalMode.fromId("YOLO"));
    }

    @Test
    void fromIdResolvesTheLegacySafeIdToManual() {
        // "safe" predates this enum's own id space entirely, from before the original
        // SAFE/ASK/YOLO merge. SAFE and ASK had already converged to the same enforcement back
        // then, so an old settings file that persisted "safe" must load as MANUAL, not AUTO.
        assertEquals(AiApprovalMode.MANUAL, AiApprovalMode.fromId("safe"));
        assertEquals(AiApprovalMode.MANUAL, AiApprovalMode.fromId("SAFE"));
    }

    @Test
    void fromIdResolvesTheLegacyAskIdToManual() {
        // "ask" was this mode's OWN id before the later ASK -> MANUAL pure-rename pass (see
        // AiApprovalMode's own doc, "History part 3"). A settings file persisted before that
        // rename must still load as MANUAL, not fall back to AUTO.
        assertEquals(AiApprovalMode.MANUAL, AiApprovalMode.fromId("ask"));
        assertEquals(AiApprovalMode.MANUAL, AiApprovalMode.fromId("ASK"));
    }

    @Test
    void fromIdFallsBackToAutoForNullOrUnrecognizedIds() {
        assertEquals(AiApprovalMode.AUTO, AiApprovalMode.fromId(null));
        assertEquals(AiApprovalMode.AUTO, AiApprovalMode.fromId(""));
        assertEquals(AiApprovalMode.AUTO, AiApprovalMode.fromId("not-a-real-mode"));
    }

    @Test
    void getIdRoundTripsThroughFromId() {
        for (AiApprovalMode mode : AiApprovalMode.values()) {
            assertEquals(mode, AiApprovalMode.fromId(mode.getId()));
        }
    }

    @Test
    void idsAreTheExpectedLowercaseStrings() {
        assertEquals("auto", AiApprovalMode.AUTO.getId());
        assertEquals("manual", AiApprovalMode.MANUAL.getId());
        assertEquals("yolo", AiApprovalMode.YOLO.getId());
    }

    @Test
    void displayNamesMatchTheProductSpec() {
        assertEquals("Auto", AiApprovalMode.AUTO.getDisplayName());
        assertEquals("Manual", AiApprovalMode.MANUAL.getDisplayName());
        // Deliberately the lowercase string "yolo" -- a stylistic label, not a typo.
        assertEquals("yolo", AiApprovalMode.YOLO.getDisplayName());
    }

    @Test
    void autoIsTheDefaultMode() {
        // The default approval mode id persisted by AiSettings ("auto") must resolve to AUTO.
        assertEquals(AiApprovalMode.AUTO, AiApprovalMode.fromId("auto"));
        assertEquals(AiApprovalMode.AUTO, AiApprovalMode.fromId(null));
    }
}
