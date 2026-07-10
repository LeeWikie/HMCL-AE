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
import org.glavo.nbt.tag.TagType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// T10: [NbtToolSupport#navigateBestEffort] and [NbtToolSupport#describeChildren] — the helpers
/// that hand the model the sibling keys of the deepest resolvable node when an NBT path misses,
/// instead of forcing another full-tree dump.
public final class NbtToolSupportTest {

    /// Builds `{Data: {XpLevel: 5, XpTotal: 100}}`.
    private static CompoundTag sampleRoot() {
        CompoundTag root = new CompoundTag();
        CompoundTag data = new CompoundTag();
        data.addInt("XpLevel", 5);
        data.addInt("XpTotal", 100);
        root.addTag("Data", data);
        return root;
    }

    @Test
    void navigateBestEffortStopsAtDeepestResolvableNode() throws Exception {
        CompoundTag root = sampleRoot();
        CompoundTag data = (CompoundTag) root.get("Data");
        List<NbtToolSupport.Step> steps = NbtToolSupport.parsePath("Data.NoSuchKey");

        // navigate() gives up (null), navigateBestEffort() returns the last node it could reach.
        assertNull(NbtToolSupport.navigate(root, steps));
        assertSame(data, NbtToolSupport.navigateBestEffort(root, steps),
                "best-effort navigation must stop at the deepest resolvable node (Data)");
    }

    @Test
    void navigateBestEffortReturnsExactTargetForAValidPath() throws Exception {
        CompoundTag root = sampleRoot();
        List<NbtToolSupport.Step> steps = NbtToolSupport.parsePath("Data.XpLevel");

        Tag exact = NbtToolSupport.navigate(root, steps);
        assertSame(exact, NbtToolSupport.navigateBestEffort(root, steps),
                "for a fully valid path best-effort must return the same node as navigate()");
    }

    @Test
    void describeChildrenEnumeratesCompoundKeys() {
        CompoundTag data = (CompoundTag) sampleRoot().get("Data");

        String desc = NbtToolSupport.describeChildren(data);

        assertTrue(desc.startsWith("CompoundTag keys:"), desc);
        assertTrue(desc.contains("XpLevel"), desc);
        assertTrue(desc.contains("XpTotal"), desc);
    }

    @Test
    void describeChildrenReportsAnEmptyCompound() {
        String desc = NbtToolSupport.describeChildren(new CompoundTag());
        assertTrue(desc.contains("empty CompoundTag"), desc);
    }

    @Test
    void describeChildrenReportsListLength() {
        String desc = NbtToolSupport.describeChildren(new ListTag<>(TagType.INT));
        assertTrue(desc.startsWith("ListTag length: 0"), desc);
    }

    @Test
    void describeChildrenReportsAScalarLeafHasNoChildren() {
        Tag scalar = ((CompoundTag) sampleRoot().get("Data")).get("XpLevel");

        String desc = NbtToolSupport.describeChildren(scalar);

        assertTrue(desc.contains("scalar"), desc);
        assertTrue(desc.contains("no child"), desc);
    }
}
