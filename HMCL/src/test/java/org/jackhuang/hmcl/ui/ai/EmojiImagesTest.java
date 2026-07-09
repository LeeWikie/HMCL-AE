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
package org.jackhuang.hmcl.ui.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Unit coverage for the framework-free cluster/filename logic of {@link EmojiImages}
/// (BF P9: regional-indicator pairing for flags; BF P1: null-safe containsEmoji).
/// Deliberately avoids {@code toNodes}/{@code imageFor} — those need the JavaFX toolkit
/// and the network; the error-cache eviction path is covered by code review + manual check.
class EmojiImagesTest {

    /// 🇨🇳 = U+1F1E8 U+1F1F3, two regional indicators that must be consumed as ONE cluster
    /// mapping to noto-emoji's pair filename — previously split into two bogus single-RI files.
    @Test
    void flagIsOneClusterWithPairedFilename() {
        String flag = "🇨🇳"; // 🇨🇳
        assertEquals(flag.length(), EmojiImages.consumeCluster(flag, 0),
                "both regional indicators must belong to the same cluster");
        assertEquals("emoji_u1f1e8_1f1f3", EmojiImages.toFilename(flag));
    }

    /// Two consecutive flags: the first cluster must stop after exactly one RI pair and not
    /// swallow the second flag.
    @Test
    void consecutiveFlagsSplitIntoTwoClusters() {
        String flags = "🇨🇳🇺🇸"; // 🇨🇳🇺🇸
        int firstEnd = EmojiImages.consumeCluster(flags, 0);
        assertEquals(4, firstEnd, "first flag cluster is exactly two regional indicators");
        assertEquals(flags.length(), EmojiImages.consumeCluster(flags, firstEnd),
                "second flag cluster consumes the remaining pair");
    }

    /// Keycap regression: "1️⃣" (U+0031 U+FE0F U+20E3) keeps its existing behaviour —
    /// full-sequence cluster, FE0F dropped and base zero-padded in the filename.
    @Test
    void keycapClusterAndFilenameUnchanged() {
        String keycap = "1️⃣"; // 1️⃣
        assertEquals(keycap.length(), EmojiImages.consumeCluster(keycap, 0));
        assertEquals("emoji_u0031_20e3", EmojiImages.toFilename(keycap));
    }

    /// ZWJ chain regression: 👨‍👩‍👦 (man ZWJ woman ZWJ boy) is consumed as one cluster.
    @Test
    void zwjChainFullyConsumed() {
        String family = "👨‍👩‍👦"; // 👨‍👩‍👦
        assertEquals(family.length(), EmojiImages.consumeCluster(family, 0));
        assertEquals("emoji_u1f468_200d_1f469_200d_1f466",
                EmojiImages.toFilename(family.substring(0, EmojiImages.consumeCluster(family, 0))));
    }

    /// An isolated regional indicator (no second RI to pair with) must not read past the end
    /// of the string and is treated as a single-character cluster (renders as text fallback).
    @Test
    void isolatedRegionalIndicatorDoesNotOverrun() {
        String lone = "🇨"; // 🇨 alone, at end of string
        assertEquals(lone.length(), EmojiImages.consumeCluster(lone, 0));

        String loneThenAscii = "🇨A"; // 🇨 followed by a non-RI character
        assertEquals(2, EmojiImages.consumeCluster(loneThenAscii, 0),
                "a non-RI follower must not be pulled into the cluster");
    }

    /// BF P1: containsEmoji must be null-safe — imported sessions may carry null content.
    @Test
    void containsEmojiIsNullSafe() {
        assertFalse(EmojiImages.containsEmoji(null));
        assertFalse(EmojiImages.containsEmoji(""));
        assertFalse(EmojiImages.containsEmoji("plain text, no emoji"));
        assertTrue(EmojiImages.containsEmoji("bubble tea 🧋")); // 🧋
        assertTrue(EmojiImages.containsEmoji("🇨🇳")); // 🇨🇳
    }
}
