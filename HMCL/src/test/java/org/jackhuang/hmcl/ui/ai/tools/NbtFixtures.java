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
import org.glavo.nbt.tag.TagType;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/// Test-only helper for building minimal, REAL (gzip-compressed) player `.dat` NBT files under a
/// world's `playerdata/` directory — the exact file layout [NbtToolSupport#locatePlayer] reads —
/// so [TransferInventoryTool] / [CopyPlayerDataTool] tests exercise real NBT parsing/writing rather
/// than mocking it.
final class NbtFixtures {

    private NbtFixtures() {
    }

    /// Writes `<worldDir>/playerdata/<uuid>.dat`: a compound with `DataVersion`, `Health` (so a
    /// field OUTSIDE the inventory is observable as "untouched" by transfer_inventory), and an
    /// `Inventory` list containing one single-slot item compound per id in {@code itemIds}.
    static Path writePlayerData(Path worldDir, UUID uuid, int dataVersion, String... itemIds) throws Exception {
        Path dir = worldDir.resolve("playerdata");
        Files.createDirectories(dir);
        Path file = dir.resolve(uuid + ".dat");

        CompoundTag root = new CompoundTag();
        root.addInt("DataVersion", dataVersion);
        root.addFloat("Health", 20.0f);

        ListTag<CompoundTag> inventory = new ListTag<>(TagType.COMPOUND);
        for (String itemId : itemIds) {
            CompoundTag item = new CompoundTag();
            item.addString("id", itemId);
            item.addByte("Count", (byte) 1);
            inventory.addTag(item);
        }
        root.addTag("Inventory", inventory);

        write(file, root);
        return file;
    }

    private static void write(Path file, CompoundTag root) throws Exception {
        try (OutputStream out = Files.newOutputStream(file); OutputStream gzip = new GZIPOutputStream(out)) {
            NBTCodec.of().writeTag(gzip, root);
        }
    }
}
