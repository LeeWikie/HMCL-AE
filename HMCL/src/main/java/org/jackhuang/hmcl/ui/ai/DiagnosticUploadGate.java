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

/// Pure precondition gate for the diagnostic-upload trigger (see
/// {@code AISettingsPage#onUploadDiagnosticClicked}).
///
/// Before this class existed, nothing outside {@code AIMainPage#maybeShowPrivacyConsent} (the
/// one-time first-run dialog) ever read {@code AIMainPage#hasPrivacyConsent()} — the diagnostic
/// upload path packaged and POSTed the session trace unconditionally, so withdrawing (or never
/// granting) consent had no data flow to actually block. {@link #check} makes that marker
/// load-bearing, alongside the pre-existing "nothing recorded yet" trace-enabled guard.
///
/// Deliberately free of JavaFX types — both inputs are plain booleans the caller reads from
/// {@code AIMainPage.hasPrivacyConsent()} / {@code AiSettings.isTraceEnabled()} — so this is
/// unit-testable without a JavaFX toolkit.
final class DiagnosticUploadGate {

    private DiagnosticUploadGate() {
    }

    enum Result {
        /// Both preconditions hold; the upload may proceed.
        OK,
        /// The AI privacy notice hasn't been (or is no longer) acknowledged. Checked FIRST — before
        /// the trace-enabled flag — so consent is never bypassed by a UI path that only looked at
        /// the trace toggle.
        NEEDS_CONSENT,
        /// Consent is fine, but tracing is off so there is nothing to upload.
        NO_TRACE
    }

    static Result check(boolean hasPrivacyConsent, boolean traceEnabled) {
        if (!hasPrivacyConsent) {
            return Result.NEEDS_CONSENT;
        }
        if (!traceEnabled) {
            return Result.NO_TRACE;
        }
        return Result.OK;
    }
}
