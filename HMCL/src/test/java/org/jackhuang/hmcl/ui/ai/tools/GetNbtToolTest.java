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
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Wave-3 group ④ coverage for {@link GetNbtTool}:
///   - T5: a missing instance fails with the shared candidate-list envelope (via
///     {@link NbtToolSupport#requireInstance} reusing {@link InstanceToolSupport#availableInstanceNames});
///   - T10: a path that misses reports the keys that DO exist at the deepest resolvable node.
public final class GetNbtToolTest {

    private final GetNbtTool tool = new GetNbtTool();

    /// Writes a minimal real (gzip) `level.dat`: `{Data: {XpLevel: <xp>}}`.
    private static void writeLevelDat(Path worldDir, int xp) throws Exception {
        Files.createDirectories(worldDir);
        CompoundTag root = new CompoundTag();
        CompoundTag data = new CompoundTag();
        data.addInt("XpLevel", xp);
        root.addTag("Data", data);
        try (OutputStream out = Files.newOutputStream(worldDir.resolve("level.dat"));
             OutputStream gzip = new GZIPOutputStream(out)) {
            NBTCodec.of().writeTag(gzip, root);
        }
    }

    @Test
    void missingInstanceFailsWithCandidateEnvelope() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Existing");

            ToolResult result = tool.execute(Map.of("instance", "DoesNotExist",
                    "world", "MyWorld", "file", "level.dat", "nbtPath", "Data.XpLevel"));

            assertFalse(result.isSuccess());
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(result.getError().contains("does not exist"), result.getError());
            assertTrue(result.getError().contains("Existing"),
                    "should list the real instance names: " + result.getError());
        }
    }

    @Test
    void missingPathEnumeratesSiblingKeys() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            fx.createInstance("Inst");
            Path worldDir = fx.repository().getRunDirectory("Inst").resolve("saves").resolve("MyWorld");
            writeLevelDat(worldDir, 7);

            ToolResult result = tool.execute(Map.of("instance", "Inst",
                    "world", "MyWorld", "file", "level.dat", "nbtPath", "Data.NoSuchKey"));

            assertFalse(result.isSuccess());
            assertTrue(ToolFailures.isWellFormedEnvelope(result.getError()),
                    "not a well-formed envelope: " + result.getError());
            assertTrue(result.getError().contains("Retryable: yes"),
                    "a typo'd path is retryable: " + result.getError());
            assertTrue(result.getError().contains("CompoundTag keys:"),
                    "must enumerate the keys that exist at the deepest node: " + result.getError());
            assertTrue(result.getError().contains("XpLevel"),
                    "must list the real sibling key: " + result.getError());
        }
    }
}
