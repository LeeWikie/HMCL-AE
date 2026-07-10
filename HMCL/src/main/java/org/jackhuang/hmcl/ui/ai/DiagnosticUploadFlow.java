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

import javafx.application.Platform;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// The one-tap diagnostic-upload trigger: packages the current session's trace (already
/// redacted at write time) and POSTs it to the feedback endpoint, returning a short reference id.
///
/// Extracted out of {@code AISettingsPage} (where the "上传诊断信息" row originally lived) so the
/// main page's pinned "反馈" sidebar entry can invoke the exact same flow directly — no
/// re-implementation, no navigating into {@link AISettingsPage} first. Both callers pass in
/// whichever {@link AiSettings} instance they already hold; this class owns no state of its own.
final class DiagnosticUploadFlow {

    private DiagnosticUploadFlow() {
    }

    /// Entry point: gates on privacy consent and trace-availability, then confirms with the user
    /// before handing off to {@link #upload}.
    static void trigger(AiSettings aiSettings) {
        // Gate on privacy consent BEFORE the trace-enabled check: consent must never be bypassable
        // just because tracing happens to be on. See DiagnosticUploadGate for why this exists.
        switch (DiagnosticUploadGate.check(AIMainPage.hasPrivacyConsent(), aiSettings.isTraceEnabled())) {
            case NEEDS_CONSENT:
                Controllers.confirm(
                        i18n("ai.diag.need_consent"),
                        i18n("ai.diag.need_consent.title"), MessageType.QUESTION,
                        () -> AIMainPage.requestPrivacyConsent(() -> trigger(aiSettings)),
                        null);
                return;
            case NO_TRACE:
                Controllers.dialog(i18n("ai.diag.no_trace"),
                        i18n("ai.diag.no_trace.title"), MessageType.INFO);
                return;
            case OK:
                break;
        }
        Controllers.confirm(
                i18n("ai.diag.confirm"),
                i18n("ai.settings.data.upload_diag"), MessageType.QUESTION,
                () -> upload(aiSettings), () -> {});
    }

    private static void upload(AiSettings aiSettings) {
        Thread worker = new Thread(() -> {
            try {
                org.jackhuang.hmcl.ai.AiSessionStore store =
                        new org.jackhuang.hmcl.ai.AiSessionStore(SettingsManager.localConfigDirectory());
                store.load();
                String sessionId = store.getCurrentSessionId();
                Path trace = sessionId == null ? null
                        : org.jackhuang.hmcl.ai.trace.TraceRecorder.traceFile(sessionId);
                if (trace == null || !Files.exists(trace)) {
                    Platform.runLater(() -> Controllers.showToast(i18n("ai.diag.session_no_trace")));
                    return;
                }
                // Same session the trace file was resolved for, above — so the accompanying
                // simplified `ui-trace.jsonl` (see UiTraceBuilder) describes exactly one session,
                // not some other notion of "current".
                org.jackhuang.hmcl.ai.AiSession session = store.getSession(sessionId);
                java.util.List<org.jackhuang.hmcl.ai.llm.LlmMessage> uiMessages =
                        session == null ? null : session.getMessages();
                org.jackhuang.hmcl.ai.diagnostic.DiagnosticUploader.UploadResult result =
                        org.jackhuang.hmcl.ai.diagnostic.DiagnosticUploader.upload(
                                trace, org.jackhuang.hmcl.Metadata.VERSION,
                                org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS.name(),
                                aiSettings.getModel(), null, uiMessages);
                Platform.runLater(() -> {
                    if (result.ok()) {
                        Controllers.dialog(i18n("ai.diag.upload_ok", result.id()),
                                i18n("ai.diag.upload_ok.title"), MessageType.SUCCESS);
                    } else {
                        Controllers.dialog(result.error() != null ? result.error() : i18n("ai.diag.unknown_error"),
                                i18n("ai.diag.upload_failed.title"), MessageType.ERROR);
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> Controllers.showToast(i18n("ai.diag.upload_failed", ex.getMessage())));
            }
        }, "ai-diagnostic-upload");
        worker.setDaemon(true);
        worker.start();
    }
}
