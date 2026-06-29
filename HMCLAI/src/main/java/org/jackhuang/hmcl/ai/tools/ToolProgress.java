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
package org.jackhuang.hmcl.ai.tools;

import org.jetbrains.annotations.Nullable;

/// A lightweight, decoupled progress bus for long-running AI tools (downloads / installs).
///
/// Tools (or the helpers they call) publish progress through the static {@link #publish},
/// {@link #begin} and {@link #finish} methods without knowing anything about the UI. The
/// UI registers a single {@link Listener} via {@link #setListener} and renders a live
/// progress card. This keeps the {@code Tool} interface unchanged: tools stay synchronous
/// and simply emit progress as a side effect while they block on the underlying task.
///
/// Threading: publishing is allowed from any thread; the listener is responsible for
/// marshalling onto its own UI thread (e.g. {@code Platform.runLater}). Exceptions thrown
/// by the listener are swallowed so a misbehaving UI can never break a tool.
public final class ToolProgress {

    private ToolProgress() {
    }

    /// A single progress update for a tool.
    ///
    /// @param toolName an opaque label for the operation in progress (used only for display
    ///                 / debugging; the UI routes updates to the currently running tool card).
    /// @param fraction completion in {@code [0, 1]}, or a negative value for an indeterminate
    ///                 (unknown duration) state.
    /// @param message  a short human-readable phase description (e.g. "Downloading …").
    /// @param done     {@code true} for the terminal update of an operation.
    /// @param success  meaningful only when {@code done} is {@code true}.
    public record Event(String toolName, double fraction, String message, boolean done, boolean success) {
        public boolean indeterminate() {
            return fraction < 0 || Double.isNaN(fraction);
        }
    }

    /// Receives progress events. Implementations must not assume any particular thread.
    @FunctionalInterface
    public interface Listener {
        void onProgress(Event event);
    }

    private static volatile @Nullable Listener listener;

    /// Registers (or clears, when {@code null}) the single progress listener. The most
    /// recent registration wins; there is intentionally only one consumer (the chat view).
    public static void setListener(@Nullable Listener newListener) {
        listener = newListener;
    }

    /// Publishes a progress fraction. Use a negative {@code fraction} for indeterminate.
    public static void publish(String toolName, double fraction, String message) {
        emit(new Event(toolName, fraction, message, false, false));
    }

    /// Publishes the start of an operation as an indeterminate update.
    public static void begin(String toolName, String message) {
        emit(new Event(toolName, -1.0, message, false, false));
    }

    /// Publishes the terminal update of an operation.
    public static void finish(String toolName, boolean success, String message) {
        emit(new Event(toolName, success ? 1.0 : -1.0, message, true, success));
    }

    private static void emit(Event event) {
        Listener current = listener;
        if (current != null) {
            try {
                current.onProgress(event);
            } catch (Throwable ignored) {
                // A broken UI listener must never break a tool's execution.
            }
        }
    }
}
