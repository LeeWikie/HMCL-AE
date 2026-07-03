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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Tracks estimated AI spend per calendar day and holds a configurable daily limit (USD), so the UI
/// can warn as the day's spend approaches the cap and block further requests once it is exceeded —
/// the launcher's guard against a runaway agent quietly burning a pay-as-you-go key.
///
/// Per-response cost itself is computed elsewhere (from token usage × the model's per-million prices);
/// this class only accumulates those amounts by day and persists them to a small JSON file.
@NotNullByDefault
public final class SpendTracker {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /// Keep at most this many past days of history so the file can't grow without bound.
    private static final int MAX_DAYS = 120;

    /// Serialized shape of the spend file.
    @SuppressWarnings({"FieldMayBeFinal", "CanBeFinal"})
    private static final class Data {
        double dailyLimitUsd = 0.0;
        Map<String, Double> spendByDay = new LinkedHashMap<>();
    }

    private final Path file;
    private Data data = new Data();

    public SpendTracker(Path file) {
        this.file = file;
        load();
    }

    /// Reloads state from disk; a missing or unreadable file leaves the tracker empty.
    public synchronized void load() {
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Data loaded = GSON.fromJson(json, Data.class);
            if (loaded != null) {
                if (loaded.spendByDay == null) {
                    loaded.spendByDay = new LinkedHashMap<>();
                }
                data = loaded;
            }
        } catch (Exception ignored) {
            // No file yet, or corrupt — start clean rather than fail.
        }
    }

    private synchronized void save() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Best-effort; losing a spend datapoint must never break a chat turn.
        }
    }

    /// The configured daily spend limit in USD; {@code 0} means no limit.
    public synchronized double getDailyLimitUsd() {
        return data.dailyLimitUsd;
    }

    public synchronized void setDailyLimitUsd(double usd) {
        data.dailyLimitUsd = Math.max(0.0, usd);
        save();
    }

    /// Adds {@code cost} USD to the given day's running total. No-op for non-positive costs.
    public synchronized void record(LocalDate day, double cost) {
        if (cost <= 0.0 || Double.isNaN(cost) || Double.isInfinite(cost)) {
            return;
        }
        data.spendByDay.merge(day.toString(), cost, Double::sum);
        prune();
        save();
    }

    public void record(double cost) {
        record(LocalDate.now(), cost);
    }

    /// Total estimated spend recorded for the given day (USD).
    public synchronized double spent(LocalDate day) {
        return data.spendByDay.getOrDefault(day.toString(), 0.0);
    }

    public double spentToday() {
        return spent(LocalDate.now());
    }

    /// True when a daily limit is set and today's spend has reached or passed it.
    public boolean isOverLimit() {
        double limit = getDailyLimitUsd();
        return limit > 0.0 && spentToday() >= limit;
    }

    /// Fraction (0..1+) of today's spend against the limit, or 0 when no limit is set.
    public double todayUsageRatio() {
        double limit = getDailyLimitUsd();
        return limit > 0.0 ? spentToday() / limit : 0.0;
    }

    private void prune() {
        if (data.spendByDay.size() <= MAX_DAYS) {
            return;
        }
        List<String> keys = new ArrayList<>(data.spendByDay.keySet());
        keys.sort(String::compareTo); // ISO dates sort chronologically
        for (int i = 0; i < keys.size() - MAX_DAYS; i++) {
            data.spendByDay.remove(keys.get(i));
        }
    }
}
