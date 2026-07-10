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

/// Callback supplied by the UI so the tool-execution layer can ask the user to
/// confirm a dangerous operation before it runs. Implementations block the
/// calling (agent) thread until the user decides and return {@code true} to
/// proceed, {@code false} to decline.
///
/// Implementations MAY also time out and fail safe to {@code false} if the user never responds
/// within a bounded window (the shipped UI implementation does, at 120s/180s) — in that case the
/// caller-facing outcome is currently indistinguishable from an explicit user decline (both surface
/// to the model as "the user declined to confirm this operation"), since there is no third state
/// for "no response / dialog dismissed without a decision".
@FunctionalInterface
public interface ToolConfirmHandler {
    boolean confirm(String toolName, String summary);
}
