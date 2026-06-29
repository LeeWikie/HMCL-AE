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
package org.jackhuang.hmcl.ui.ai.tools;

import javafx.application.Platform;
import javafx.scene.input.Clipboard;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// Reads the operating system clipboard so the agent can pick up text the user just
/// copied — typically a crash report, an error line, a mod name, or a log snippet pasted
/// from a website or another window. The model has no other way to reach the OS
/// clipboard, which makes this a genuine capability rather than something it can do on
/// its own.
///
/// The JavaFX [`Clipboard`] is thread-confined, so the read is marshalled onto the FX
/// application thread and the calling (background) tool thread waits with a short timeout
/// to avoid blocking the agent loop indefinitely.
///
/// Permission level: READ_ONLY. It only reads the clipboard and never modifies it.
@NotNullByDefault
public final class ReadClipboardTool implements ToolSpec {

    /// Hard cap on returned characters to avoid flooding the model context.
    private static final int MAX_CHARS = 20_000;
    /// How long the tool thread waits for the FX read before giving up.
    private static final long TIMEOUT_SECONDS = 5;

    @Override
    public String getName() {
        return "read_clipboard";
    }

    @Override
    public String getDescription() {
        return "Reads the current plain-text content of the system clipboard (useful when the user says "
                + "they copied a crash report, error message, log, or mod name). Takes no parameters. "
                + "Returns the clipboard text, or a note if it is empty / not text. Read-only.";
    }

    @Override
    public ToolPermission getPermission() {
        return ToolPermission.READ_ONLY;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        AtomicReference<String> holder = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            Platform.runLater(() -> {
                try {
                    Clipboard clipboard = Clipboard.getSystemClipboard();
                    holder.set(clipboard.hasString() ? clipboard.getString() : null);
                } catch (Throwable e) {
                    error.set(e);
                } finally {
                    latch.countDown();
                }
            });
        } catch (IllegalStateException e) {
            return ToolResult.failure("The UI toolkit is not running; cannot read the clipboard.");
        }

        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return ToolResult.failure("Timed out while reading the clipboard.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Interrupted while reading the clipboard.");
        }

        if (error.get() != null) {
            return ToolResult.failure("Failed to read the clipboard: " + error.get().getMessage());
        }

        String text = holder.get();
        if (text == null || text.isEmpty()) {
            return ToolResult.success("The clipboard is empty or does not contain text.");
        }

        boolean truncated = text.length() > MAX_CHARS;
        if (truncated) {
            text = text.substring(0, MAX_CHARS);
        }
        return ToolResult.success("Clipboard contents (" + text.length() + " chars"
                + (truncated ? ", truncated" : "") + "):\n" + text);
    }
}
