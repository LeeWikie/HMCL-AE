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
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.setting.Accounts;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/// Creates an offline Minecraft account with the given username and adds it to HMCL.
///
/// Reuses [`Accounts#FACTORY_OFFLINE`] (`OfflineAccountFactory.create(username, uuid)`)
/// — the exact same path HMCL's own "add offline account" UI uses. The AI should call
/// this instead of editing account files via shell.
@NotNullByDefault
public final class AddOfflineAccountTool implements Tool {

    @Override
    public String getName() {
        return "add_offline_account";
    }

    @Override
    public String getDescription() {
        return "Creates an offline (no Mojang/Microsoft) Minecraft account and adds it to HMCL. "
                + "Parameters: username (required); select (optional boolean, default true — make it the active account). "
                + "Offline accounts cannot join online-mode servers or Realms. "
                + "For a real Microsoft account use microsoft_login instead.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String username = String.valueOf(parameters.getOrDefault("username", "")).trim();
        if (username.isEmpty()) {
            return ToolResult.failure("username is required.");
        }
        Object selectObj = parameters.getOrDefault("select", Boolean.TRUE);
        boolean select = !(selectObj instanceof Boolean) || (Boolean) selectObj;

        try {
            CompletableFuture<String> future = new CompletableFuture<>();
            Runnable task = () -> {
                try {
                    // Reject duplicate offline names so we don't create confusing duplicates.
                    boolean exists = Accounts.getAccounts().stream()
                            .anyMatch(a -> a instanceof OfflineAccount && username.equalsIgnoreCase(a.getProfileName()));
                    if (exists) {
                        future.complete("An offline account named '" + username + "' already exists.");
                        return;
                    }
                    OfflineAccount account = Accounts.FACTORY_OFFLINE.create(username, null);
                    Accounts.getAccounts().add(account);
                    if (select) {
                        Accounts.setSelectedAccount(account);
                    }
                    future.complete("✓ Created offline account '" + username + "' (uuid=" + account.getProfileID() + ")"
                            + (select ? " and set it as the active account." : "."));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            };
            if (Platform.isFxApplicationThread()) task.run();
            else Platform.runLater(task);
            return ToolResult.success(future.get(15, TimeUnit.SECONDS));
        } catch (Throwable e) {
            return ToolResult.failure("Failed to create offline account: " + e.getMessage());
        }
    }
}
