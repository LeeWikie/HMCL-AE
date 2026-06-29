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

import org.glavo.nbt.io.NBTCodec;
import org.glavo.nbt.tag.CompoundTag;
import org.glavo.nbt.tag.ListTag;
import org.glavo.nbt.tag.Tag;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/// A read-only tool that lists the multiplayer servers an instance has saved in
/// its `servers.dat` (the server list shown on the in-game Multiplayer screen).
///
/// `servers.dat` is an (uncompressed) NBT file. This reuses HMCL's bundled NBT
/// library directly ([`NBTCodec`], the same codec used by HMCL's NBT editor),
/// which auto-detects compression, so no raw stream handling is needed. Instance
/// paths come from [`HMCLGameRepository#getRunDirectory(String)`].
///
/// Permission level: READ_ONLY. It never modifies `servers.dat`.
@NotNullByDefault
public final class ListServersTool implements Tool {

    @Override
    public String getName() {
        return "list_servers";
    }

    @Override
    public String getDescription() {
        return "Lists the multiplayer servers saved in an instance's servers.dat (the in-game Multiplayer server "
                + "list): each server's display name and address. "
                + "Parameter: instance (optional; defaults to the currently selected instance). Read-only. "
                + "If servers.dat does not exist, the user has not added any servers in this instance.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Profile profile;
        try {
            profile = Profiles.getSelectedProfile();
        } catch (Throwable e) {
            return ToolResult.failure("No profile is currently selected: " + e.getMessage());
        }
        HMCLGameRepository repository = profile.getRepository();

        Object instanceObj = parameters.get("instance");
        String instance;
        if (instanceObj instanceof String && !((String) instanceObj).trim().isEmpty()) {
            instance = ((String) instanceObj).trim();
        } else {
            @Nullable String selected = Profiles.getSelectedInstance();
            if (selected == null) {
                return ToolResult.failure("No instance is selected and no 'instance' parameter was given.");
            }
            instance = selected;
        }

        try {
            if (!repository.hasVersion(instance)) {
                return ToolResult.failure("Instance '" + instance + "' does not exist in the selected profile.");
            }
        } catch (Throwable e) {
            return ToolResult.failure("Failed to verify instance '" + instance + "': " + e.getMessage());
        }

        Path serversFile;
        try {
            serversFile = repository.getRunDirectory(instance).resolve("servers.dat");
        } catch (Throwable e) {
            return ToolResult.failure("Failed to resolve the run directory of '" + instance + "': " + e.getMessage());
        }

        if (!Files.isRegularFile(serversFile)) {
            return ToolResult.success("Instance '" + instance + "' has no saved multiplayer servers "
                    + "(servers.dat not found at " + serversFile + ").");
        }

        CompoundTag root;
        try {
            Tag tag = NBTCodec.of().readTag(serversFile);
            if (!(tag instanceof CompoundTag)) {
                return ToolResult.failure("servers.dat of instance '" + instance + "' is malformed (root is not a compound).");
            }
            root = (CompoundTag) tag;
        } catch (Throwable e) {
            return ToolResult.failure("Failed to read servers.dat of instance '" + instance + "': " + e.getMessage());
        }

        if (!(root.get("servers") instanceof ListTag<?> servers) || servers.isEmpty()) {
            return ToolResult.success("Instance '" + instance + "' has an empty multiplayer server list.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Multiplayer servers of instance '").append(instance).append("' (").append(servers.size()).append("):\n");
        int index = 1;
        for (Tag entry : servers) {
            if (!(entry instanceof CompoundTag server)) {
                continue;
            }
            String name = server.getStringOrNull("name");
            String ip = server.getStringOrNull("ip");
            sb.append("  ").append(index++).append(". ")
                    .append(name == null || name.isEmpty() ? "(unnamed)" : name);
            if (ip != null && !ip.isEmpty()) {
                sb.append("  —  ").append(ip);
            }
            sb.append('\n');
        }

        return ToolResult.success(sb.toString().trim());
    }
}
