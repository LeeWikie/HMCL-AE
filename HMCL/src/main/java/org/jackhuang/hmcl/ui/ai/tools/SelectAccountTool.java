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
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.setting.Accounts;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/// Selects (activates) a logged-in account by username, so the next game launch uses it.
///
/// Reuses [`Accounts#setSelectedAccount(Account)`]. Match is by profile name
/// (case-insensitive). Use list_accounts first to see the available names.
@NotNullByDefault
public final class SelectAccountTool implements Tool {

    @Override
    public String getName() {
        return "select_account";
    }

    @Override
    public String getDescription() {
        return "Selects (activates) a logged-in account by username so the next launch uses it. "
                + "Parameter: username (required, case-insensitive). Use list_accounts to see names.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String username = String.valueOf(parameters.getOrDefault("username", "")).trim();
        if (username.isEmpty()) {
            return ToolResult.failure("username is required.");
        }
        try {
            CompletableFuture<String> future = new CompletableFuture<>();
            Runnable task = () -> {
                try {
                    List<Account> accounts = new ArrayList<>(Accounts.getAccounts());
                    Account match = accounts.stream()
                            .filter(a -> username.equalsIgnoreCase(a.getProfileName()))
                            .findFirst().orElse(null);
                    if (match == null) {
                        StringBuilder names = new StringBuilder();
                        for (Account a : accounts) names.append("\n  - ").append(a.getProfileName());
                        future.complete("No account named '" + username + "'. Available accounts:" + names);
                        return;
                    }
                    Accounts.setSelectedAccount(match);
                    future.complete("✓ Selected account '" + match.getProfileName() + "'.");
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            };
            if (Platform.isFxApplicationThread()) task.run();
            else Platform.runLater(task);
            return ToolResult.success(future.get(10, TimeUnit.SECONDS));
        } catch (Throwable e) {
            return ToolResult.failure("Failed to select account: " + e.getMessage());
        }
    }
}
