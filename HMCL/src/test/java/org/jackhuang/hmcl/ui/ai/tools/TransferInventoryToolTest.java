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

/// Covers [TransferInventoryTool]'s parameter-resolution/validation branches (missing from/to,
/// same-uuid refusal, world not found, source/destination player not found) and the success path
/// (ONLY the `Inventory` list moves; everything else on the destination, e.g. `Health`, is
/// untouched; a backup of the destination is created first; a `DataVersion` mismatch is reported as
/// a warning, not a failure), using real gzip-compressed NBT player files built via
/// [NbtFixtures] — no mocks, mirroring [DeleteInstanceToolTest]'s real-filesystem approach.
public final class TransferInventoryToolTest {

    private final TransferInventoryTool tool = new TransferInventoryTool();

    @Test
    void reportsCorrectMetadata() {
        assertEquals("transfer_inventory", tool.getName());
        assertTrue(tool.getDescription().toLowerCase().contains("inventory"));
    }

    @Test
    void missingFromOrToFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Files.createDirectories(fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld"));
            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "MyWorld", "from", "Steve"));
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
            assertTrue(result.getError().contains("resolve to the same UUID"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void worldNotFoundFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "NoSuchWorld",
                    "from", UUID.randomUUID().toString(), "to", UUID.randomUUID().toString()));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("was not found"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void sourcePlayerNotFoundFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld");
            Files.createDirectories(worldDir);
            UUID to = UUID.randomUUID();
            NbtFixtures.writePlayerData(worldDir, to, 3700, "minecraft:stone");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "MyWorld",
                    "from", UUID.randomUUID().toString(), "to", to.toString()));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Could not find source player data"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void destinationPlayerNotFoundFails() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld");
            Files.createDirectories(worldDir);
            UUID from = UUID.randomUUID();
            NbtFixtures.writePlayerData(worldDir, from, 3700, "minecraft:stone");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "MyWorld",
                    "from", from.toString(), "to", UUID.randomUUID().toString()));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Could not find destination player data"), "unexpected message: " + result.getError());
        }
    }

    @Test
    void refusesWithEnvelopeWhileWorldIsLocked() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld");
            Files.createDirectories(worldDir);
            UUID from = UUID.randomUUID();
            UUID to = UUID.randomUUID();
            NbtFixtures.writePlayerData(worldDir, from, 3700, "minecraft:diamond_sword");
            Path destFile = NbtFixtures.writePlayerData(worldDir, to, 3700, "minecraft:stone");
            byte[] destBefore = java.nio.file.Files.readAllBytes(destFile);

            // Simulate a running game session holding the world's session.lock (same-JVM
            // FileChannel#tryLock holder, exactly like GameResourceGuardTest).
            try (java.nio.channels.FileChannel holder = java.nio.channels.FileChannel.open(
                    worldDir.resolve("session.lock"),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
                java.nio.channels.FileLock held = holder.tryLock();
                assertTrue(held != null, "test setup: could not take the session lock");

                ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "MyWorld",
                        "from", from.toString(), "to", to.toString()));

                assertFalse(result.isSuccess(), "a locked world must refuse the transfer");
                assertTrue(org.jackhuang.hmcl.ai.tools.ToolFailures.isWellFormedEnvelope(result.getError()),
                        "not a well-formed envelope: " + result.getError());
                assertTrue(result.getError().contains("Retryable: later"),
                        "unexpected classification: " + result.getError());
                assertTrue(result.getError().contains("MyWorld"),
                        "should name the world: " + result.getError());
            }

            org.junit.jupiter.api.Assertions.assertArrayEquals(destBefore, Files.readAllBytes(destFile),
                    "the refused transfer must not touch the destination file");
        }
    }

    @Test
    void transfersOnlyTheInventoryLeavingOtherFieldsUntouched() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld");
            Files.createDirectories(worldDir);
            UUID from = UUID.randomUUID();
            UUID to = UUID.randomUUID();
            NbtFixtures.writePlayerData(worldDir, from, 3700, "minecraft:diamond_sword", "minecraft:shield");
            Path destFile = NbtFixtures.writePlayerData(worldDir, to, 3700, "minecraft:stone");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "MyWorld",
                    "from", from.toString(), "to", to.toString()));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());

            CompoundTag destination = NbtToolSupport.readCompound(destFile);
            assertEquals(20.0f, destination.getFloatOrNull("Health"), "Health must be untouched by an inventory transfer");
            Tag invTag = destination.get("Inventory");
            assertTrue(invTag instanceof ListTag<?>);
            ListTag<?> inventory = (ListTag<?>) invTag;
            assertEquals(2, inventory.size(), "destination must now have the SOURCE's inventory (2 items)");
            assertEquals("minecraft:diamond_sword", ((CompoundTag) inventory.getTag(0)).getStringOrNull("id"));
            assertEquals("minecraft:shield", ((CompoundTag) inventory.getTag(1)).getStringOrNull("id"));

            // The destination must have been backed up before being overwritten.
            long backups = Files.list(worldDir.resolve("playerdata"))
                    .filter(p -> p.getFileName().toString().startsWith(to + ".dat.bak-"))
                    .count();
            assertEquals(1, backups, "expected exactly one backup of the destination file");
        }
    }

    @Test
    void warnsOnDataVersionMismatchButStillSucceeds() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld");
            Files.createDirectories(worldDir);
            UUID from = UUID.randomUUID();
            UUID to = UUID.randomUUID();
            NbtFixtures.writePlayerData(worldDir, from, 3700, "minecraft:diamond_sword");
            NbtFixtures.writePlayerData(worldDir, to, 2586, "minecraft:stone");

            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "MyWorld",
                    "from", from.toString(), "to", to.toString()));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("WARNING: DataVersion differs"), "unexpected message: " + result.getOutput());
        }
    }

    @Test
    void includeEnderChestCopiesEnderItemsToo() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");
            Path worldDir = fx.repository().getRunDirectory("Existing").resolve("saves").resolve("MyWorld");
            Files.createDirectories(worldDir);
            UUID from = UUID.randomUUID();
            UUID to = UUID.randomUUID();
            Path sourceFile = NbtFixtures.writePlayerData(worldDir, from, 3700, "minecraft:diamond_sword");
            NbtFixtures.writePlayerData(worldDir, to, 3700, "minecraft:stone");

            // Add an EnderItems list to the source, on top of what writePlayerData already wrote.
            CompoundTag source = NbtToolSupport.readCompound(sourceFile);
            ListTag<CompoundTag> ender = new ListTag<>(org.glavo.nbt.tag.TagType.COMPOUND);
            CompoundTag enderItem = new CompoundTag();
            enderItem.addString("id", "minecraft:ender_chest");
            ender.addTag(enderItem);
            source.addTag("EnderItems", ender);
            NbtToolSupport.writeTag(sourceFile, source, true);

            ToolResult result = tool.execute(Map.of("instance", "Existing", "world", "MyWorld",
                    "from", from.toString(), "to", to.toString(), "includeEnderChest", true));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(result.getOutput().contains("ender chest included"), "unexpected message: " + result.getOutput());

            Path destFile = worldDir.resolve("playerdata").resolve(to + ".dat");
            CompoundTag destination = NbtToolSupport.readCompound(destFile);
            Tag destEnder = destination.get("EnderItems");
            assertTrue(destEnder instanceof ListTag<?>);
            assertEquals(1, ((ListTag<?>) destEnder).size());
        }
    }
}
