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

import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;
import java.util.Map;

/// Domain tool for HMCL's account system — never shell out / hand-edit account files for any of
/// this; these delegate to HMCL's own account manager and native login dialog.
@NotNullByDefault
public final class AccountTool implements ToolSpec {

    private final ListAccountsTool list = new ListAccountsTool();
    private final AddOfflineAccountTool addOffline = new AddOfflineAccountTool();
    private final SelectAccountTool select = new SelectAccountTool();
    private final MicrosoftLoginTool microsoftLogin = new MicrosoftLoginTool();
    private final SetSkinTool setSkin = new SetSkinTool();

    @Override
    public String getName() {
        return "account";
    }

    @Override
    public String getDescription() {
        return "Minecraft account management. Parameter 'action' (required): "
                + "list — logged-in accounts (username, type, UUID), marks the selected one; "
                + "add_offline(username[, select]) — create an offline account, select defaults to true; "
                + "select(username) — activate a logged-in account by username (case-insensitive); "
                + "microsoft_login — opens HMCL's native OAuth sign-in dialog, no parameters, user completes it; "
                + "set_skin(...) — set an account's skin (+cape for offline accounts); see set_skin's own "
                + "parameter list: username (optional), source (local/little_skin/csl_api/steve/alex/default), "
                + "skinPath, capePath (offline only), cslApi (offline only), model (auto/wide/slim). Offline "
                + "accounts support every source; online (Microsoft/authlib-injector) accounts support only a "
                + "local PNG upload.";
    }

    @Override
    public boolean supportsStructuredSchema() {
        return true;
    }

    @Override
    public String getInputSchemaJson() {
        return """
               {
                 "$schema": "https://json-schema.org/draft/2020-12/schema",
                 "type": "object",
                 "properties": {
                   "action": {"type": "string", "enum": ["list", "add_offline", "select", "microsoft_login", "set_skin"], "description": "Which account operation to perform."},
                   "username": {"type": "string", "description": "add_offline/select/set_skin: the account's username; for set_skin, optional and defaults to the selected account."},
                   "select": {"type": "boolean", "description": "add_offline: make the new account active; defaults to true."},
                   "source": {"type": "string", "description": "set_skin: local / little_skin / csl_api / steve / alex / default."},
                   "skinPath": {"type": "string", "description": "set_skin: absolute path to a local .png skin, for source=local."},
                   "capePath": {"type": "string", "description": "set_skin: absolute path to a local .png cape (offline accounts only)."},
                   "cslApi": {"type": "string", "description": "set_skin: root URL of a CustomSkinLoader/Yggdrasil skin-station, for source=csl_api (offline only)."},
                   "model": {"type": "string", "description": "set_skin: auto / wide / slim."}
                 },
                 "required": ["action"]
               }
               """;
    }

    @Override
    public ToolPermission getPermission(Map<String, Object> parameters) {
        String action = actionOf(parameters);
        return "list".equals(action) ? ToolPermission.READ_ONLY : ToolPermission.CONTROLLED_WRITE;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String action = actionOf(parameters);
        return switch (action) {
            case "list" -> list.execute(parameters);
            case "add_offline" -> addOffline.execute(parameters);
            case "select" -> select.execute(parameters);
            case "microsoft_login" -> microsoftLogin.execute(parameters);
            case "set_skin" -> setSkin.execute(parameters);
            default -> ToolResult.failure("Unknown action '" + action + "'. Valid actions: list, add_offline, "
                    + "select, microsoft_login, set_skin.");
        };
    }

    private static String actionOf(Map<String, Object> parameters) {
        Object action = parameters.get("action");
        return action != null ? action.toString().trim().toLowerCase(Locale.ROOT) : "";
    }
}
