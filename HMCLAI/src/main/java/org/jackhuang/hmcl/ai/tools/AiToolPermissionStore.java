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
package org.jackhuang.hmcl.ai.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import org.jackhuang.hmcl.ai.AiApprovalMode;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/// Persists user-facing AI tool permission overrides.
///
/// The global approval mode still lives in {@code AiSettings}; this store only
/// records per-tool overrides. A missing tool entry means "follow global".
@NotNullByDefault
public final class AiToolPermissionStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public enum OverrideMode {
        FOLLOW_GLOBAL("follow-global"),
        SAFE("safe"),
        ASK("ask"),
        YOLO("yolo");

        private final String id;

        OverrideMode(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public static OverrideMode fromId(@Nullable String id) {
            if (id == null) return FOLLOW_GLOBAL;
            for (OverrideMode mode : values()) {
                if (mode.id.equalsIgnoreCase(id)) return mode;
            }
            return FOLLOW_GLOBAL;
        }

        public static OverrideMode fromApprovalMode(AiApprovalMode mode) {
            return switch (mode) {
                case SAFE -> SAFE;
                case ASK -> ASK;
                case YOLO -> YOLO;
            };
        }
    }

    @SuppressWarnings({"FieldMayBeFinal", "CanBeFinal"})
    private static final class PersistedData {
        @SerializedName("toolOverrides")
        @Nullable
        private Map<String, String> toolOverrides = null;
    }

    private final Path file;
    private final Map<String, OverrideMode> overrides = new LinkedHashMap<>();

    public AiToolPermissionStore(Path file) {
        this.file = file;
    }

    public void load() throws IOException, JsonParseException {
        overrides.clear();
        if (!Files.exists(file)) return;
        String json = Files.readString(file, StandardCharsets.UTF_8);
        PersistedData data = GSON.fromJson(json, PersistedData.class);
        if (data != null && data.toolOverrides != null) {
            for (Map.Entry<String, String> entry : data.toolOverrides.entrySet()) {
                OverrideMode mode = OverrideMode.fromId(entry.getValue());
                if (mode != OverrideMode.FOLLOW_GLOBAL) {
                    overrides.put(entry.getKey(), mode);
                }
            }
        }
    }

    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        PersistedData data = new PersistedData();
        data.toolOverrides = new LinkedHashMap<>();
        for (Map.Entry<String, OverrideMode> entry : overrides.entrySet()) {
            if (entry.getValue() != OverrideMode.FOLLOW_GLOBAL) {
                data.toolOverrides.put(entry.getKey(), entry.getValue().getId());
            }
        }
        Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
    }

    public OverrideMode getOverride(String toolName) {
        return overrides.getOrDefault(toolName, OverrideMode.FOLLOW_GLOBAL);
    }

    public void setOverride(String toolName, OverrideMode mode) {
        if (mode == OverrideMode.FOLLOW_GLOBAL) {
            overrides.remove(toolName);
        } else {
            overrides.put(toolName, mode);
        }
    }

    public Map<String, OverrideMode> getOverrides() {
        return Collections.unmodifiableMap(overrides);
    }
}
