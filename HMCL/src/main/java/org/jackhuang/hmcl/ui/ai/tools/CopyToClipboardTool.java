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
import javafx.scene.input.ClipboardContent;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// Writes text to the operating system clipboard so the user can paste it elsewhere —
/// e.g. a launch command the agent assembled, a server address, a fixed config snippet,
/// or a ready-to-share summary. Like reading the clipboard, this is something the model
/// cannot do by itself.
///
/// The JavaFX [`Clipboard`] must be touched on the FX application thread, so the write is
/// marshalled there and the calling tool thread waits briefly for confirmation.
///
/// Permission level: CONTROLLED_WRITE. It only replaces the clipboard contents and never
/// touches files or game state.
@NotNullByDefault
public final class CopyToClipboardTool implements Tool {

    private static final long TIMEOUT_SECONDS = 5;

    @Override
    public String getName() {
        return "copy_to_clipboard";
    }

    @Override
    public String getDescription() {
        return "Copies the given text to the system clipboard so the user can paste it into another "
                + "application. Parameter: 'text' (required, the text to copy). Use this for share text, "
                + "commands, addresses, or config snippets you want the user to paste elsewhere.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String text = InstanceToolSupport.string(parameters, "text");
        if (text == null) {
            text = InstanceToolSupport.string(parameters, "query");
        }
        if (text == null) {
            return ToolResult.failure("Parameter 'text' is required and must not be empty.");
        }

        final String payload = text;
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            Platform.runLater(() -> {
                try {
                    ClipboardContent content = new ClipboardContent();
                    content.putString(payload);
                    Clipboard.getSystemClipboard().setContent(content);
                } catch (Throwable e) {
                    error.set(e);
                } finally {
                    latch.countDown();
                }
            });
        } catch (IllegalStateException e) {
            return ToolResult.failure("The UI toolkit is not running; cannot write to the clipboard.");
        }

        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return ToolResult.failure("Timed out while writing to the clipboard.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Interrupted while writing to the clipboard.");
        }

        if (error.get() != null) {
            return ToolResult.failure("Failed to write to the clipboard: " + error.get().getMessage());
        }

        return ToolResult.success("Copied " + payload.length() + " character(s) to the clipboard.");
    }
}
