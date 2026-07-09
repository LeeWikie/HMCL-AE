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

import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.setting.Accounts;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/// Test-only fixture that adds one or more throwaway
/// [org.jackhuang.hmcl.auth.offline.OfflineAccount]s to the real [Accounts] singleton's shared,
/// process-wide account list, and on {@link #close()} removes exactly what it added and restores
/// whichever account was selected beforehand.
///
/// This deliberately does NOT call {@link Accounts#init()}: that method loads from
/// [org.jackhuang.hmcl.setting.SettingsManager] (which isn't bootstrapped in a plain unit test),
/// attempts a real (network) login for the selected account, and — once called — permanently wires
/// up listeners that persist every future account-list change to disk for the rest of the JVM's
/// life. None of that is needed here: {@link Accounts#getAccounts()} / {@link
/// Accounts#getSelectedAccount()} / {@link Accounts#setSelectedAccount} are plain static state
/// accessible before {@code init()}, which is exactly the same pattern
/// {@code AddOfflineAccountTool} itself uses.
final class AccountsFixture implements AutoCloseable {

    private final List<Account> added = new ArrayList<>();
    private final Account previousSelected;

    AccountsFixture() {
        previousSelected = Accounts.getSelectedAccount();
    }

    /// Adds a fresh offline account named {@code username} (a random, collision-free UUID) to the
    /// shared account list and returns it.
    Account addOfflineAccount(String username) {
        Account account = Accounts.FACTORY_OFFLINE.create(username, UUID.randomUUID());
        Accounts.getAccounts().add(account);
        added.add(account);
        return account;
    }

    @Override
    public void close() {
        Accounts.getAccounts().removeAll(added);
        Accounts.setSelectedAccount(previousSelected);
    }
}
