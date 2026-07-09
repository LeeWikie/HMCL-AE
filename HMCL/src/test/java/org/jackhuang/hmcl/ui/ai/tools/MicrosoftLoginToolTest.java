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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [MicrosoftLoginTool]'s metadata contract only.
///
/// This tool takes no parameters and its entire "logic" is dispatching
/// {@code Controllers.dialog(new CreateAccountPane(Accounts.FACTORY_MICROSOFT))} via
/// {@link javafx.application.Platform#runLater} — i.e. calling {@code execute()} would genuinely
/// open a real native OAuth login dialog window backed by {@code Controllers}' live scene graph,
/// which needs a fully bootstrapped HMCL application (a decorated primary stage etc.), not just the
/// bare JavaFX toolkit {@code JavaFXLauncher} starts for these tests. That is exactly the kind of
/// "real JavaFX window" this suite is told to avoid, so `execute()` is deliberately not invoked
/// here; the tool's whole reachable, mock-free contract is its declared name/description.
public final class MicrosoftLoginToolTest {

    private final MicrosoftLoginTool tool = new MicrosoftLoginTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("microsoft_login", tool.getName());
    }

    @Test
    void descriptionDocumentsTheNoParameterOAuthDialogContractAndForbidsShellLogin() {
        String description = tool.getDescription();
        assertTrue(description.contains("no parameters"),
                "must document that this tool takes no parameters: " + description);
        assertTrue(description.toLowerCase().contains("oauth"), "must mention the OAuth flow: " + description);
        assertTrue(description.toLowerCase().contains("never try to log in via shell"),
                "must forbid the model from attempting a shell/file-based Microsoft login: " + description);
    }
}
