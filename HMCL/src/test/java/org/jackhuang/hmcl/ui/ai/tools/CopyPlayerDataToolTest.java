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
import org.glavo.nbt.tag.ListTag;
import org.glavo.nbt.tag.Tag;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [CopyPlayerDataTool]'s parameter-resolution/validation branches (missing from/to,
/// same-uuid refusal, world not found, source player not found) and both success branches: copying
/// onto a destination that does NOT yet exist (created, no backup) and onto one that already does
/// (overwritten, backed up first) — using real gzip-compressed NBT player files built via
/// [NbtFixtures], no mocks.
public final class CopyPlayerDataToolTest {

    private final CopyPlayerDataTool tool = new CopyPlayerDataTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("copy_player_data", tool.getName());
        assertTrue(tool.getDescription().toLowerCase().contains("player"));
    }

    @Test
    void missingFromOrToFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Files.createDirectories(fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld"));
            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "MyWorld", "to", "Alex"));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("'from' and 'to'"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void sameUuidFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Files.createDirectories(fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld"));
            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "MyWorld",
                    "from", "Steve", "to", "Steve"));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("nothing to copy"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void worldNotFoundFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "NoSuchWorld",
                    "from", UUID.randomUUID().toString(), "to", UUID.randomUUID().toString()));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("does not exist"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void sourcePlayerNotFoundFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld");
            Files.createDirectories(worldDir);

            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "MyWorld",
                    "from", UUID.randomUUID().toString(), "to", UUID.randomUUID().toString()));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Could not find source player data"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void copiesFullPlayerCompoundOntoANewDestinationWithoutABackup() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld");
            Files.createDirectories(worldDir);
            UUID from = UUID.randomUUID();
            UUID to = UUID.randomUUID();
            NbtFixtures.writePlayerData(worldDir, from, 3700, "minecraft:diamond_sword", "minecraft:shield");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "MyWorld",
                    "from", from.toString(), "to", to.toString()));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("(created)"), "unexpected message: " + result.getOutput());
            assertTrue(result.getOutput().contains("(none — file was new)"), "unexpected message: " + result.getOutput());

            Path destFile = worldDir.resolve("playerdata").resolve(to + ".dat");
            CompoundTag destination = NbtToolSupport.readCompound(destFile);
            assertEquals(20.0f, destination.getFloatOrNull("Health"));
            Tag invTag = destination.get("Inventory");
            assertTrue(invTag instanceof ListTag<?>);
            assertEquals(2, ((ListTag<?>) invTag).size());
        }
    }

    @Test
    void overwritesAnExistingDestinationAfterBackingItUp() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld");
            Files.createDirectories(worldDir);
            UUID from = UUID.randomUUID();
            UUID to = UUID.randomUUID();
            NbtFixtures.writePlayerData(worldDir, from, 3700, "minecraft:diamond_sword");
            NbtFixtures.writePlayerData(worldDir, to, 3700, "minecraft:stone");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "MyWorld",
                    "from", from.toString(), "to", to.toString()));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("(overwritten)"), "unexpected message: " + result.getOutput());

            Path destFile = worldDir.resolve("playerdata").resolve(to + ".dat");
            CompoundTag destination = NbtToolSupport.readCompound(destFile);
            Tag invTag = destination.get("Inventory");
            assertEquals(1, ((ListTag<?>) invTag).size());
            assertEquals("minecraft:diamond_sword", ((CompoundTag) ((ListTag<?>) invTag).getTag(0)).getStringOrNull("id"));

            long backups = Files.list(worldDir.resolve("playerdata"))
                    .filter(p -> p.getFileName().toString().startsWith(to + ".dat.bak-"))
                    .count();
            assertEquals(1, backups, "expected exactly one backup of the pre-existing destination file");
        }
    }
}
