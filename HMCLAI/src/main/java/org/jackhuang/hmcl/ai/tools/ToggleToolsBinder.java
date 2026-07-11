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

/// Binds a boolean setting toggle to a {@link ToolRegistry} so flipping it takes effect
/// immediately — no restart:
///
/// - toggle ON  → the given tools are registered and become discoverable by the model on its very
///   next turn;
/// - toggle OFF → they are {@link ToolRegistry#unregister unregistered} entirely, so the model's
///   tool list simply does not contain them (undiscoverable — not "present but erroring").
///
/// The initial state is applied at bind time, replacing the old one-shot
/// `if (isEnabled()) register(...)` startup wiring whose changes only landed after a restart. The
/// listener holds strong references to the tools/registry, matching their app-lifetime scope in the
/// chat page.
///
/// Generalized from the original web-access-only binder: the exact same register/unregister-on-a-
/// live-boolean mechanism now drives every hot tool toggle — 联网工具 (web_search/web_fetch),
/// shell, and NBT tools — so each takes effect the moment its switch flips (陈旧态批 §3.4/§3.5).
/// The bound observable may itself be a derived expression (e.g. web_search binds to
/// `webAccessEnabled AND searchEnabled`), which is how a compound enable-condition stays a single
/// live source rather than a value baked in once at startup.
@NotNullByDefault
public final class ToggleToolsBinder {

    private ToggleToolsBinder() {
    }

    /// Applies the current value of {@code enabled} to {@code registry} (registering or
    /// unregistering every tool in {@code tools}), then keeps the registry in sync with any later
    /// changes of the observable.
    public static void bind(ObservableValue<Boolean> enabled, ToolRegistry registry, Tool... tools) {
        apply(Boolean.TRUE.equals(enabled.getValue()), registry, tools);
        enabled.addListener((observable, oldValue, newValue) ->
                apply(Boolean.TRUE.equals(newValue), registry, tools));
    }

    private static void apply(boolean on, ToolRegistry registry, Tool[] tools) {
        for (Tool tool : tools) {
            if (on) {
                registry.register(tool);
            } else {
                registry.unregister(tool.getName());
            }
        }
    }
}
