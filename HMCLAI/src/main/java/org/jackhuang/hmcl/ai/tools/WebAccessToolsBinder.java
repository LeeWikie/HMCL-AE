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

import javafx.beans.value.ObservableValue;
import org.jetbrains.annotations.NotNullByDefault;

/// Binds the "启用联网工具" (web access) setting to a {@link ToolRegistry} so the toggle takes
/// effect immediately — no restart:
///
/// - toggle ON  → the given web tools (web_search / web_fetch) are registered and become
///   discoverable by the model on its very next turn;
/// - toggle OFF → they are {@link ToolRegistry#unregister unregistered} entirely, so the model's
///   tool list simply does not contain them (undiscoverable — not "present but erroring").
///
/// The initial state is applied at bind time, replacing the old one-shot
/// `if (isWebAccessEnabled()) register(...)` startup wiring whose changes only landed after a
/// restart. The listener holds strong references to the tools/registry, matching their
/// app-lifetime scope in the chat page.
@NotNullByDefault
public final class WebAccessToolsBinder {

    private WebAccessToolsBinder() {
    }

    /// Applies the current value of {@code enabled} to {@code registry} (registering or
    /// unregistering every tool in {@code webTools}), then keeps the registry in sync with any
    /// later changes of the observable.
    public static void bind(ObservableValue<Boolean> enabled, ToolRegistry registry, Tool... webTools) {
        apply(Boolean.TRUE.equals(enabled.getValue()), registry, webTools);
        enabled.addListener((observable, oldValue, newValue) ->
                apply(Boolean.TRUE.equals(newValue), registry, webTools));
    }

    private static void apply(boolean on, ToolRegistry registry, Tool[] webTools) {
        for (Tool tool : webTools) {
            if (on) {
                registry.register(tool);
            } else {
                registry.unregister(tool.getName());
            }
        }
    }
}
