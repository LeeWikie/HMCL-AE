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
import org.glavo.nbt.tag.ValueTag;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.setting.Profile;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// Reads a single value (or subtree) at a dotted NBT path inside a save file, e.g.
/// `Data.Player.XpLevel` or `Data.Player.Inventory[0].id`.
///
/// The path is resolved by hand (compound keys via `.`, list/array elements via `[index]`)
/// for predictable behaviour. Scalars are printed directly; compounds/lists are printed as
/// SNBT.
///
/// Permission level: READ_ONLY.
@NotNullByDefault
public final class GetNbtTool implements Tool {

    @Override
    public String getName() {
        return "get_nbt";
    }

    @Override
    public String getDescription() {
        return "Reads one value or subtree at a dotted NBT path from a save file. READ-ONLY. "
                + "Parameters: instance (optional); EITHER path (relative to saves/) OR world + file; "
                + "nbtPath (required, e.g. 'Data.Player.XpLevel' or 'Data.Player.Inventory[0].id' — use '.' for "
                + "compound keys and [n] for list/array indices). Returns the value (scalars printed plainly, "
                + "compounds/lists as SNBT text).";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        try {
            Profile profile = NbtToolSupport.requireProfile();
            String instance = NbtToolSupport.requireInstance(profile, parameters);
            Path savesDir = NbtToolSupport.savesDir(profile, instance);
            Path file = NbtToolSupport.resolveTargetFile(savesDir, parameters);

            String nbtPath = NbtToolSupport.str(parameters, "nbtPath");
            if (nbtPath == null) {
                return ToolResult.failure("Missing required 'nbtPath' parameter.");
            }

            Tag root = NbtToolSupport.readTag(file);
            List<NbtToolSupport.Step> steps = NbtToolSupport.parsePath(nbtPath);
            Tag target = NbtToolSupport.navigate(root, steps);
            if (target == null) {
                return ToolResult.failure("No tag found at path '" + nbtPath + "' in " + savesDir.relativize(file) + ".");
            }

            String value;
            if (target instanceof ValueTag<?> v) {
                value = "(" + target.getType().name() + ") " + v.getAsString();
            } else {
                value = "(" + target.getType().name() + ")\n" + NbtToolSupport.toSNBT(target);
            }
            return ToolResult.success(nbtPath + " = " + value);
        } catch (NbtToolSupport.NbtToolException e) {
            return ToolResult.failure(e.getMessage());
        } catch (Throwable e) {
            return ToolResult.failure("Failed to read NBT value: " + e);
        }
    }
}
