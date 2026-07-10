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

import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.setting.Accounts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [AddOfflineAccountTool]'s same-name semantics (CP-6): an offline account whose name
/// already exists is now REPLACED in place with the freshly-created one, matching HMCL's own
/// "add account" UI ([org.jackhuang.hmcl.ui.account.CreateAccountPane]#completeLogin) rather than
/// being rejected — so AI and native paths behave identically.
///
/// `execute()` hops through [javafx.application.Platform#runLater] and blocks on a
/// [java.util.concurrent.CompletableFuture], so — like [SelectAccountToolTest] — the
/// account-mutating cases are gated behind `JavaFXLauncher#isStarted` and are skipped (not failed)
/// in a headless environment. Each such test snapshots and restores the shared account list itself,
/// because the tool creates a brand-new account object that [AccountsFixture] could not track.
public final class AddOfflineAccountToolTest {

    private final AddOfflineAccountTool tool = new AddOfflineAccountTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("add_offline_account", tool.getName());
        assertTrue(tool.getDescription().contains("username"));
    }

    @Test
    void missingUsernameFails() {
        ToolResult result = tool.execute(Map.of());
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("username"), () -> "unexpected message: " + result.getError());
    }

    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    void duplicateOfflineNameReplacesInPlaceLikeNativeUi() {
        List<Account> snapshot = new ArrayList<>(Accounts.getAccounts());
        Account prevSelected = Accounts.getSelectedAccount();
        try {
            Account original = Accounts.FACTORY_OFFLINE.create("Steve", UUID.randomUUID());
            Accounts.getAccounts().add(original);
            int sizeBefore = Accounts.getAccounts().size();
            int index = Accounts.getAccounts().indexOf(original);

            // Case-insensitive match, like the tool's own detection.
            ToolResult result = tool.execute(Map.of("username", "sTEVE"));

            assertTrue(result.isSuccess(), () -> "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("Replaced"),
                    () -> "must report a replacement, not a fresh creation: " + result.getOutput());
            // Replaced, not duplicated: no net growth.
            assertEquals(sizeBefore, Accounts.getAccounts().size(),
                    "a same-name offline account must replace the existing one, not add a second entry");
            // Exactly one offline account named Steve remains.
            long steves = Accounts.getAccounts().stream()
                    .filter(a -> a instanceof OfflineAccount && "Steve".equalsIgnoreCase(a.getProfileName()))
                    .count();
            assertEquals(1L, steves, "exactly one same-name offline account should remain");
            // It sits at the original index, is the freshly-created object (new credentials), and is selected.
            Account now = Accounts.getAccounts().get(index);
            assertNotSame(original, now, "the entry should have been swapped for the new account object");
            assertTrue(now instanceof OfflineAccount && "Steve".equalsIgnoreCase(now.getProfileName()));
            assertSame(now, Accounts.getSelectedAccount(), "select defaults to true, so the new account is active");
        } finally {
            Accounts.getAccounts().setAll(snapshot);
            Accounts.setSelectedAccount(prevSelected);
        }
    }

    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    void freshNameIsCreatedNormally() {
        List<Account> snapshot = new ArrayList<>(Accounts.getAccounts());
        Account prevSelected = Accounts.getSelectedAccount();
        try {
            int sizeBefore = Accounts.getAccounts().size();

            ToolResult result = tool.execute(Map.of("username", "BrandNewName123", "select", false));

            assertTrue(result.isSuccess(), () -> "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("Created"),
                    () -> "a never-seen name should be a fresh creation: " + result.getOutput());
            assertEquals(sizeBefore + 1, Accounts.getAccounts().size(), "a fresh name should add exactly one entry");
        } finally {
            Accounts.getAccounts().setAll(snapshot);
            Accounts.setSelectedAccount(prevSelected);
        }
    }
}
