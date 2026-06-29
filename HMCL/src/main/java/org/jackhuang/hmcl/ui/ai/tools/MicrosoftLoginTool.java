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
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.account.CreateAccountPane;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Map;

/// Starts a Microsoft account login by opening HMCL's native account-creation dialog
/// (which drives the OAuth browser flow). The user finishes the login in that dialog.
///
/// This deliberately reuses HMCL's own UI ([`CreateAccountPane`] with
/// [`Accounts#FACTORY_MICROSOFT`]) — the AI must NEVER attempt to perform Microsoft
/// OAuth via shell commands or by editing files; that cannot work and is unsafe.
@NotNullByDefault
public final class MicrosoftLoginTool implements Tool {

    @Override
    public String getName() {
        return "microsoft_login";
    }

    @Override
    public String getDescription() {
        return "Starts a Microsoft account login by opening HMCL's native login dialog (OAuth browser flow). "
                + "Takes no parameters. The user completes the sign-in in the dialog that appears. "
                + "ALWAYS use this for Microsoft accounts — never try to log in via shell or by editing files.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        try {
            Platform.runLater(() -> Controllers.dialog(new CreateAccountPane(Accounts.FACTORY_MICROSOFT)));
            return ToolResult.success("Opened the Microsoft login dialog. "
                    + "Ask the user to complete the sign-in there; once done, the new account becomes available "
                    + "(verify with list_accounts).");
        } catch (Throwable e) {
            return ToolResult.failure("Failed to open the Microsoft login dialog: " + e.getMessage());
        }
    }
}
