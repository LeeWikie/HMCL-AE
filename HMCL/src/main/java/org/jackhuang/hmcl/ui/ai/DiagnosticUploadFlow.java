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
                        "上传诊断信息前需要先确认「AI 隐私与数据说明」。是否现在查看并确认？",
                        "需要先确认隐私说明", MessageType.QUESTION,
                        () -> AIMainPage.requestPrivacyConsent(() -> trigger(aiSettings)),
                        null);
                return;
            case NO_TRACE:
                Controllers.dialog("尚未开启「记录诊断 Trace」，没有可上传的记录。请先开启该选项，复现问题后再上传。",
                        "无诊断记录", MessageType.INFO);
                return;
            case OK:
                break;
        }
        Controllers.confirm(
                "将把当前会话的完整消息、工具调用与结果打包上传（已自动脱敏 API Key 等敏感信息），仅用于排查你反馈的问题。确定上传吗？",
                "上传诊断信息", MessageType.QUESTION,
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
                    Platform.runLater(() -> Controllers.showToast("当前会话还没有 Trace 记录，无法上传"));
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
                        Controllers.dialog("上传成功，反馈编号：" + result.id() + "\n如需继续沟通可向开发者提供此编号。",
                                "上传成功", MessageType.SUCCESS);
                    } else {
                        Controllers.dialog(result.error() != null ? result.error() : "未知错误",
                                "上传失败", MessageType.ERROR);
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> Controllers.showToast("上传失败：" + ex.getMessage()));
            }
        }, "ai-diagnostic-upload");
        worker.setDaemon(true);
        worker.start();
    }
}
