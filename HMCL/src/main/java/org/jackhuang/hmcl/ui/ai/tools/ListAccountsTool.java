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

/// Read-only tool: lists the logged-in Minecraft accounts and marks the selected one.
///
/// Reuses HMCL's account system directly ([`Accounts#getAccounts()`],
/// [`Accounts#getSelectedAccount()`], [`Accounts#getLoginType(...)`]). The AI should
/// call this instead of shelling out, since HMCL owns the account store.
@NotNullByDefault
public final class ListAccountsTool implements Tool {

    @Override
    public String getName() {
        return "list_accounts";
    }

    @Override
    public String getDescription() {
        return "Lists the logged-in Minecraft accounts (username, type: offline/microsoft/authlibInjector, UUID) "
                + "and marks the currently selected one. Takes no parameters. Read-only. "
                + "Use this instead of shell commands — HMCL owns the account store.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        try {
            CompletableFuture<String> future = new CompletableFuture<>();
            Runnable task = () -> {
                try {
                    List<Account> accounts = new ArrayList<>(Accounts.getAccounts());
                    Account selected = Accounts.getSelectedAccount();
                    if (accounts.isEmpty()) {
                        future.complete("No accounts are logged in. "
                                + "Use add_offline_account for an offline account, or microsoft_login for a Microsoft account.");
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("Logged-in accounts (").append(accounts.size()).append("):\n");
                    for (Account account : accounts) {
                        boolean isSelected = account == selected;
                        String type;
                        try {
                            type = Accounts.getLoginType(Accounts.getAccountFactory(account));
                        } catch (Throwable t) {
                            type = "unknown";
                        }
                        sb.append(isSelected ? "  * " : "  - ")
                                .append(account.getProfileName())
                                .append(" [").append(type).append(']')
                                .append(" uuid=").append(account.getProfileID());
                        if (isSelected) sb.append("  (selected)");
                        sb.append('\n');
                    }
                    future.complete(sb.toString().trim());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            };
            if (Platform.isFxApplicationThread()) task.run();
            else Platform.runLater(task);
            return ToolResult.success(future.get(10, TimeUnit.SECONDS));
        } catch (Throwable e) {
            return ToolResult.failure("Failed to list accounts: " + e.getMessage());
        }
    }
}
