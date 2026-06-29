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

import org.glavo.nbt.tag.Tag;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.setting.Profile;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.Map;

/// Reads any NBT save file inside an instance's `saves/` directory and shows its contents
/// as a readable indented tree.
///
/// Compression (gzip for `level.dat`/playerdata, none for `servers.dat`) is auto-detected
/// by the bundled NBT codec, so the caller only needs to point at the file.
///
/// Permission level: READ_ONLY.
@NotNullByDefault
public final class ReadNbtTool implements Tool {

    @Override
    public String getName() {
        return "read_nbt";
    }

    @Override
    public String getDescription() {
        return "Reads a Minecraft NBT save file and shows its contents as an indented tree. READ-ONLY. "
                + "Parameters: instance (optional, defaults to selected); and EITHER path (file path relative to "
                + "the instance's saves/ directory, e.g. 'MyWorld/level.dat') OR world + file (e.g. world='MyWorld', "
                + "file='level.dat'); maxDepth (optional integer, default 6, use -1 for unlimited depth). "
                + "Compression is auto-detected. The path is restricted to the saves/ directory for safety.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        try {
            Profile profile = NbtToolSupport.requireProfile();
            String instance = NbtToolSupport.requireInstance(profile, parameters);
            Path savesDir = NbtToolSupport.savesDir(profile, instance);
            Path file = NbtToolSupport.resolveTargetFile(savesDir, parameters);

            int maxDepth = 6;
            Object md = parameters.get("maxDepth");
            if (md instanceof Number) {
                maxDepth = ((Number) md).intValue();
            } else if (md instanceof String) {
                try {
                    maxDepth = Integer.parseInt(((String) md).trim());
                } catch (NumberFormatException ignored) {
                }
            }

            Tag tag = NbtToolSupport.readTag(file);
            String tree = NbtToolSupport.renderTree(tag, maxDepth);
            return ToolResult.success("NBT contents of " + savesDir.relativize(file) + ":\n" + tree.trim());
        } catch (NbtToolSupport.NbtToolException e) {
            return ToolResult.failure(e.getMessage());
        } catch (Throwable e) {
            return ToolResult.failure("Failed to read NBT: " + e);
        }
    }
}
