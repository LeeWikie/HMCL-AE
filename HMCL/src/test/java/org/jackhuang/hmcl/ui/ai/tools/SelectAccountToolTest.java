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
import org.jackhuang.hmcl.setting.Accounts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [SelectAccountTool]'s username resolution against the real [Accounts] singleton: the
/// missing-parameter hard failure, an unknown username (reported as a successful "not found"
/// result, not a tool failure — matching the tool's own contract), and successfully activating a
/// known account case-insensitively. A real
/// [org.jackhuang.hmcl.auth.offline.OfflineAccount][org.jackhuang.hmcl.auth.offline.OfflineAccount]
/// is added to (and removed from) the shared [Accounts#getAccounts()] list for each test via
/// [AccountsFixture], mirroring how [ProfileFixture] scopes other real static state instead of
/// mocking it.
///
/// `execute()` always hops through [javafx.application.Platform#runLater] (or runs directly if
/// already on the FX thread) and blocks on a [java.util.concurrent.CompletableFuture], so — like
/// [SetInstanceJvmArgsToolTest] — the account-mutating tests are gated behind
/// `JavaFXLauncher#isStarted` and simply don't run in a headless environment where the JavaFX
/// toolkit failed to start, rather than failing the build.
public final class SelectAccountToolTest {

    private final SelectAccountTool tool = new SelectAccountTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("select_account", tool.getName());
        assertTrue(tool.getDescription().contains("username"));
    }

    @Test
    void missingUsernameFails() {
        ToolResult result = tool.execute(Map.of());
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("username"), "unexpected message: " + result.getError());
    }

    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    void unknownUsernameReportsAvailableAccountsWithoutFailing() {
        try (AccountsFixture fx = new AccountsFixture()) {
            Account known = fx.addOfflineAccount("Steve");

            ToolResult result = tool.execute(Map.of("username", "NoSuchPlayer"));

            assertTrue(result.isSuccess(), "an unknown username is reported, not a hard failure: " + result.getError());
            assertTrue(result.getOutput().contains("No account named 'NoSuchPlayer'"),
                    "unexpected message: " + result.getOutput());
            assertTrue(result.getOutput().contains("Steve"), "must list the available account names");
            assertNotEquals(known, Accounts.getSelectedAccount());
        }
    }

    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    void selectsTheMatchingAccountCaseInsensitively() {
        try (AccountsFixture fx = new AccountsFixture()) {
            Account steve = fx.addOfflineAccount("Steve");
            fx.addOfflineAccount("Alex");

            ToolResult result = tool.execute(Map.of("username", "sTEVE"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("Selected account 'Steve'"), "unexpected message: " + result.getOutput());
            assertEquals(steve, Accounts.getSelectedAccount());
        }
    }
}
