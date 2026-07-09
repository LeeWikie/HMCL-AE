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
package org.jackhuang.hmcl.ui.ai;

import org.junit.jupiter.api.Test;

import static org.jackhuang.hmcl.ui.ai.DiagnosticUploadGate.Result.NEEDS_CONSENT;
import static org.jackhuang.hmcl.ui.ai.DiagnosticUploadGate.Result.NO_TRACE;
import static org.jackhuang.hmcl.ui.ai.DiagnosticUploadGate.Result.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Locks in the consent-gating fix: a diagnostic-upload attempt must be refused whenever the AI
/// privacy notice hasn't been (or is no longer) acknowledged, regardless of the trace-enabled flag
/// — before this fix, nothing checked consent at all, so withdrawing/never granting it had no data
/// flow to actually block (the marker file was purely cosmetic).
public class DiagnosticUploadGateTest {

    @Test
    void refusesWhenPrivacyConsentIsMissingEvenIfTraceEnabled() {
        assertEquals(NEEDS_CONSENT, DiagnosticUploadGate.check(false, true));
    }

    @Test
    void consentIsCheckedBeforeTraceEnabledSoItCanNeverBeBypassed() {
        // Both preconditions false: must report the consent failure, not the trace one — a caller
        // that only inspected the trace-enabled branch could otherwise skip the consent gate.
        assertEquals(NEEDS_CONSENT, DiagnosticUploadGate.check(false, false));
    }

    @Test
    void refusesWhenTraceDisabledButConsentGranted() {
        assertEquals(NO_TRACE, DiagnosticUploadGate.check(true, false));
    }

    @Test
    void allowsWhenConsentGrantedAndTraceEnabled() {
        assertEquals(OK, DiagnosticUploadGate.check(true, true));
    }
}
