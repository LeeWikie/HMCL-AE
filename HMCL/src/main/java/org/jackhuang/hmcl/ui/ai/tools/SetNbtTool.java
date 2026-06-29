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

import org.glavo.nbt.tag.CompoundTag;
import org.glavo.nbt.tag.Tag;
import org.glavo.nbt.tag.ValueTag;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.setting.Profile;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// Edits an existing value at a dotted NBT path inside a save file. HIGH-RISK (writes to disk).
///
/// Safety contract enforced here: the target tag must already exist (this tool edits, it does
/// not invent keys); the original value is echoed before and after; a timestamped backup of the
/// file is taken before writing; the write is atomic and preserves the file's compression; and
/// if the containing world is currently locked (game running) the write is refused.
///
/// Scalars are mutated in place (parsed into the existing tag's type). Replacing a whole
/// compound/list subtree is supported only when its parent is a compound, with the new value
/// supplied as SNBT.
///
/// Permission level: WRITE / HIGH-RISK.
@NotNullByDefault
public final class SetNbtTool implements Tool {

    @Override
    public String getName() {
        return "set_nbt";
    }

    @Override
    public String getDescription() {
        return "Edits an EXISTING value at a dotted NBT path in a save file (e.g. set Data.Player.XpLevel). "
                + "WRITE / HIGH-RISK: always takes a timestamped .bak backup first, writes atomically, preserves "
                + "compression, and refuses if the world is currently open in-game. Parameters: instance (optional); "
                + "EITHER path (relative to saves/) OR world + file; nbtPath (required); value (required). For scalar "
                + "tags the value is parsed into the existing tag's type (true/false allowed for byte). To replace a "
                + "whole compound/list, give value as SNBT (only when its parent is a compound). The target must "
                + "already exist. Tell the user to close the game first; recommend backing up the world.";
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
            Object valueObj = parameters.get("value");
            if (valueObj == null) {
                return ToolResult.failure("Missing required 'value' parameter.");
            }
            String value = String.valueOf(valueObj);

            // Refuse to write into a world that is currently open in the game.
            Path rel = savesDir.relativize(file);
            if (rel.getNameCount() >= 2) {
                Path worldDir = savesDir.resolve(rel.getName(0));
                if (Files.isRegularFile(worldDir.resolve("level.dat")) && NbtToolSupport.isWorldLocked(worldDir)) {
                    return ToolResult.failure("World '" + rel.getName(0) + "' is currently open in the game. "
                            + "Please quit to the title screen (or close Minecraft) first, then retry.");
                }
            }

            boolean gzip = NbtToolSupport.isGzip(file);
            Tag root = NbtToolSupport.readTag(file);

            List<NbtToolSupport.Step> steps = NbtToolSupport.parsePath(nbtPath);
            Tag target = NbtToolSupport.navigate(root, steps);
            if (target == null) {
                return ToolResult.failure("No tag found at path '" + nbtPath + "'. set_nbt only edits existing values; "
                        + "verify the path with get_nbt/read_nbt first.");
            }

            String before;
            String after;

            if (target instanceof ValueTag<?> scalar) {
                before = "(" + target.getType().name() + ") " + scalar.getAsString();
                after = NbtToolSupport.setScalar(scalar, value);
            } else {
                // Complex subtree replacement — needs the parent compound and a key last step.
                NbtToolSupport.Step last = steps.get(steps.size() - 1);
                if (last.isIndex) {
                    return ToolResult.failure("Replacing a list/array element at an index is not supported for "
                            + "compound/list values; only scalar list elements can be edited in place.");
                }
                @Nullable Tag parent = steps.size() == 1
                        ? root
                        : NbtToolSupport.navigate(root, steps.subList(0, steps.size() - 1));
                if (!(parent instanceof CompoundTag parentCompound)) {
                    return ToolResult.failure("Cannot replace a compound/list value whose parent is not a compound.");
                }
                before = "(" + target.getType().name() + ") " + NbtToolSupport.toSNBT(target);
                Tag newTag = NbtToolSupport.parseSNBT(value);
                Tag old = parentCompound.get(last.key);
                if (old != null) {
                    parentCompound.removeTag(old);
                }
                parentCompound.addTag(last.key, newTag);
                after = "(" + newTag.getType().name() + ") " + NbtToolSupport.toSNBT(newTag);
            }

            Path backup = NbtToolSupport.backup(file);
            NbtToolSupport.writeTag(file, root, gzip);

            StringBuilder sb = new StringBuilder();
            sb.append("Updated ").append(nbtPath).append(" in ").append(rel).append('\n');
            sb.append("  before: ").append(before).append('\n');
            sb.append("  after:  ").append(after).append('\n');
            sb.append("Backup saved to: ").append(backup != null ? backup.getFileName() : "(none)");
            return ToolResult.success(sb.toString());
        } catch (NbtToolSupport.NbtToolException e) {
            return ToolResult.failure(e.getMessage());
        } catch (Throwable e) {
            return ToolResult.failure("Failed to set NBT value: " + e);
        }
    }
}
