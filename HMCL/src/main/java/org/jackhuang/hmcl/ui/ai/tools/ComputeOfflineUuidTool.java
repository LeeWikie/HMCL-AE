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

import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Map;
import java.util.UUID;

/// Computes the offline-mode UUID for a given username, exactly as the vanilla Minecraft
/// client does (`UUID.nameUUIDFromBytes("OfflinePlayer:<name>")`). The username is
/// case-sensitive. Useful for locating a player's `playerdata/<uuid>.dat` file when only the
/// account name is known.
///
/// Permission level: READ_ONLY (pure computation, touches no files).
@NotNullByDefault
public final class ComputeOfflineUuidTool implements Tool {

    @Override
    public String getName() {
        return "compute_offline_uuid";
    }

    @Override
    public String getDescription() {
        return "Computes the offline-mode UUID for a Minecraft username (the same algorithm the vanilla client "
                + "uses: UUID.nameUUIDFromBytes of 'OfflinePlayer:<name>'). READ-ONLY, no files touched. The "
                + "username is CASE-SENSITIVE. Parameter: username (required). Returns the UUID in both dashed and "
                + "undashed forms (playerdata files are named '<dashed-uuid>.dat').";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String username = NbtToolSupport.str(parameters, "username");
        if (username == null) {
            return ToolResult.failure("Missing required 'username' parameter.");
        }
        UUID uuid = NbtToolSupport.computeOfflineUuid(username);
        return ToolResult.success("Offline UUID for username '" + username + "' (case-sensitive):\n"
                + "  dashed:   " + uuid + "\n"
                + "  undashed: " + uuid.toString().replace("-", "") + "\n"
                + "  playerdata file: playerdata/" + uuid + ".dat");
    }
}
