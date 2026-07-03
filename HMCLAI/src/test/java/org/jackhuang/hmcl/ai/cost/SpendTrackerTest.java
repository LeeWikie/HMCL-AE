/*
 * Hello Minecraft! Launcher
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
package org.jackhuang.hmcl.ai.cost;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

public final class SpendTrackerTest {

    /// Spend accumulates per day, the limit persists, and both survive a reload.
    @Test
    public void testAccumulateAndPersist() throws IOException {
        Path dir = Files.createTempDirectory("hmcl-spend-");
        Path file = dir.resolve("ai-spend.json");
        try {
            LocalDate day = LocalDate.of(2026, 7, 3);
            SpendTracker t = new SpendTracker(file);
            t.setDailyLimitUsd(1.00);
            t.record(day, 0.30);
            t.record(day, 0.20);
            assertEquals(0.50, t.spent(day), 1e-9);

            // A fresh instance reads the same file back.
            SpendTracker reloaded = new SpendTracker(file);
            assertEquals(0.50, reloaded.spent(day), 1e-9);
            assertEquals(1.00, reloaded.getDailyLimitUsd(), 1e-9);
        } finally {
            cleanup(dir);
        }
    }

    /// isOverLimit / ratio reflect the configured cap; a zero limit means "no limit".
    @Test
    public void testLimitLogic() throws IOException {
        Path dir = Files.createTempDirectory("hmcl-spend-limit-");
        Path file = dir.resolve("ai-spend.json");
        try {
            LocalDate day = LocalDate.now();
            SpendTracker t = new SpendTracker(file);
            t.record(day, 0.80);
            t.setDailyLimitUsd(0.0); // no limit
            assertFalse(t.isOverLimit());
            assertEquals(0.0, t.todayUsageRatio(), 1e-9);

            t.setDailyLimitUsd(1.00);
            assertFalse(t.isOverLimit());
            assertEquals(0.80, t.todayUsageRatio(), 1e-9);

            t.record(day, 0.30); // now 1.10 >= 1.00
            assertTrue(t.isOverLimit());
        } finally {
            cleanup(dir);
        }
    }

    /// Non-positive / non-finite costs are ignored.
    @Test
    public void testIgnoresBadCosts() throws IOException {
        Path dir = Files.createTempDirectory("hmcl-spend-bad-");
        Path file = dir.resolve("ai-spend.json");
        try {
            LocalDate day = LocalDate.now();
            SpendTracker t = new SpendTracker(file);
            t.record(day, 0.0);
            t.record(day, -5.0);
            t.record(day, Double.NaN);
            assertEquals(0.0, t.spent(day), 1e-9);
        } finally {
            cleanup(dir);
        }
    }

    private static void cleanup(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var s = Files.walk(dir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }
}
