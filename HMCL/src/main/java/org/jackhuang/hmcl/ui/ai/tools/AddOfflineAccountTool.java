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
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.auth.offline.OfflineAccountFactory;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolParams;
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
        // Robust resolution: weak models often put the name under the wrong key (query/input/…) or
        // as "username=Steve". ToolParams handles canonical → aliases → generic keys → sole value.
        final String username = ToolParams.string(parameters, "username", "name", "player", "account");
        if (username.isEmpty()) {
            return ToolResult.failure("username is required.");
        }
        Object selectObj = parameters.getOrDefault("select", Boolean.TRUE);
        boolean select = !(selectObj instanceof Boolean) || (Boolean) selectObj;

        try {
            CompletableFuture<String> future = new CompletableFuture<>();
            Runnable task = () -> {
                try {
                    // Must NOT pass a null uuid: OfflineAccountFactory#create(String, UUID) forwards
                    // it straight into `new OfflineAccount(...)`, which does `requireNonNull(profileID)`
                    // — every brand-new username would throw NPE unconditionally. Derive the same
                    // deterministic UUID the null-aware 5-arg overload (used by the real "Add offline
                    // account" UI) falls back to when no uuid is explicitly supplied.
                    OfflineAccount account = Accounts.FACTORY_OFFLINE.create(
                            username, OfflineAccountFactory.getUUIDFromUserName(username));

                    // If an offline account with this name already exists, REPLACE it in place with the
                    // freshly-created one — the same semantics as HMCL's own "add account" UI
                    // (CreateAccountPane#completeLogin), which replaces an already-added account with the
                    // new credentials rather than rejecting it. This keeps AI and native behaviour aligned.
                    int oldIndex = -1;
                    for (int i = 0; i < Accounts.getAccounts().size(); i++) {
                        Account existing = Accounts.getAccounts().get(i);
                        if (existing instanceof OfflineAccount && username.equalsIgnoreCase(existing.getProfileName())) {
                            oldIndex = i;
                            break;
                        }
                    }
                    boolean replaced = oldIndex >= 0;
                    if (replaced) {
                        Accounts.getAccounts().remove(oldIndex);
                        Accounts.getAccounts().add(oldIndex, account);
                    } else {
                        Accounts.getAccounts().add(account);
                    }
                    if (select) {
                        Accounts.setSelectedAccount(account);
                    }
                    future.complete("✓ " + (replaced ? "Replaced existing" : "Created new")
                            + " offline account '" + username + "' (uuid=" + account.getProfileID() + ")"
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
