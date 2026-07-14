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

import javafx.application.Platform;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.event.Event;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jackhuang.hmcl.setting.VersionIconType;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/// Sets the icon of a single Minecraft instance of the selected profile — either one of HMCL's
/// built-in icons ({@link VersionIconType}) or a custom image file — mirroring exactly what the
/// native icon picker ({@link org.jackhuang.hmcl.ui.versions.VersionIconDialog}) does when the user
/// clicks a tile:
///
/// - Built-in icon: `setting.iconProperty().setValue(type)` — the same single call the dialog's
///   `createIcon(...)` handler performs. The `icon` property is a plain per-instance
///   {@link GameSettings.Instance} setting (NOT an inheritable/override-gated property like memory or
///   running-directory), so — like the native dialog — nothing is touched in `overrideProperties`.
/// - Custom image: `repository.setVersionIconFile(id, file)` copies the picked image into the
///   instance's version root as `icon.<ext>` (HMCL validates the extension against
///   {@link FXUtils#IMAGE_EXTENSIONS} and replaces any previous `icon.*`), then
///   `setting.iconProperty().setValue(VersionIconType.DEFAULT)` — again exactly the dialog's
///   `exploreIcon()` path. With the type left at DEFAULT, {@link HMCLGameRepository#getVersionIconImage}
///   renders the copied file.
/// - Reset to automatic: `repository.deleteIconFile(id)` removes any custom `icon.*` and the type is
///   set to DEFAULT, so HMCL falls back to auto-detecting the icon from the loader / game version
///   (the DEFAULT branch of `getVersionIconImage`). This composes the native public
///   {@link HMCLGameRepository#deleteIconFile} + the DEFAULT type; without deleting the file, DEFAULT
///   would keep showing a leftover custom image rather than truly reverting to automatic.
///
/// After mutating, it fires `repository.onVersionIconChanged` (with the current instance as the event
/// source), the same event the dialog fires on accept, so the launcher's instance list refreshes its
/// icon immediately.
///
/// Read-only report (no `iconType` and no `imagePath`): reports the instance's current icon — a
/// built-in type, a custom image, or automatic detection.
///
/// The mutation runs on the JavaFX thread because the settings property carries the UI's auto-save
/// listener and the icon-changed event drives JavaFX UI updates. Instances whose settings file is
/// read-only cannot be changed and are reported instead.
///
/// Permission level: it WRITES the instance's HMCL game-settings and (for a custom image) copies a
/// file into the instance's version folder.
@NotNullByDefault
public final class SetInstanceIconTool implements Tool {

    @Override
    public String getName() {
        return "set_instance_icon";
    }

    @Override
    public String getDescription() {
        return "Sets the icon shown for a Minecraft instance of the selected profile, reusing HMCL's own icon system. "
                + "Parameters (pass at most one of iconType / imagePath): "
                + "iconType (one of HMCL's built-in icons: " + builtinIconNames() + " — or 'auto' to reset to automatic "
                + "detection from the loader/game version); "
                + "imagePath (absolute path to a custom image file, copied into the instance's folder — must exist and be "
                + "a supported image type: " + String.join("/", FXUtils.IMAGE_EXTENSIONS) + "); "
                + "instance (optional, the instance id; defaults to the currently selected instance). "
                + "WRITES the instance's HMCL game-settings (and, for a custom image, copies the file into the instance "
                + "folder). If neither iconType nor imagePath is given it only REPORTS the current icon.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Profile profile;
        try {
            profile = Profiles.getSelectedProfile();
        } catch (Throwable e) {
            return ToolResult.failure("No profile is currently selected: " + e.getMessage());
        }
        HMCLGameRepository repository = profile.getRepository();
        if (!repository.isLoaded()) {
            return ToolResult.failure("The game repository is not loaded yet; please try again in a moment.");
        }

        // NOTE: generic aliases are NOT accepted for instance resolution — 'iconType'/'imagePath' are the
        // real value parameters and the instance resolver must not steal them via a 'query' fallback.
        InstanceToolSupport.ResolvedInstance target =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (target.failure() != null) {
            return target.failure();
        }
        final String instance = target.name();

        // Read the current icon (for the read-only report, the "previous icon" line, and no-op detection).
        final VersionIconType currentType;
        final Optional<Path> currentCustomFile;
        try {
            GameSettings.Instance existing = repository.getInstanceGameSettings(instance);
            VersionIconType value = existing != null ? existing.iconProperty().getValue() : null;
            currentType = value != null ? value : VersionIconType.DEFAULT;
            currentCustomFile = repository.getVersionIconFile(instance);
        } catch (Throwable e) {
            return ToolResult.failure("Failed to read the icon of '" + instance + "': " + e.getMessage());
        }
        final String currentDesc = describeIcon(currentType, currentCustomFile);

        // Parse the two mutually-exclusive write parameters ('icon' is accepted as an alias of 'iconType').
        String iconTypeRaw = InstanceToolSupport.string(parameters, "iconType");
        if (iconTypeRaw == null) {
            iconTypeRaw = InstanceToolSupport.string(parameters, "icon");
        }
        String imagePathRaw = InstanceToolSupport.string(parameters, "imagePath");
        if (imagePathRaw == null) {
            imagePathRaw = InstanceToolSupport.string(parameters, "image");
        }
        final boolean hasIconType = iconTypeRaw != null;
        final boolean hasImagePath = imagePathRaw != null;

        // No write parameter: degrade to a read-only report of the current icon.
        if (!hasIconType && !hasImagePath) {
            return ToolResult.success("Instance '" + instance + "' currently uses " + currentDesc + ".\n"
                    + "To change it, call this action again with 'iconType' (a built-in icon: " + builtinIconNames()
                    + ", or 'auto' to reset to automatic detection) or 'imagePath' (a custom image file).");
        }

        // Mutual exclusion: a built-in type and a custom image are two different icon sources.
        if (hasIconType && hasImagePath) {
            return ToolResult.failure("Requested both a built-in 'iconType' and a custom 'imagePath'. These are "
                    + "mutually exclusive — pass 'iconType' for a built-in icon (or 'auto' to reset to automatic), or "
                    + "'imagePath' for a custom image, not both.");
        }

        // Resolve the requested target, validating before touching anything.
        final @Nullable VersionIconType targetType;   // set for the built-in / auto path; null for a custom image
        final @Nullable Path customImage;             // set for the custom-image path; null otherwise
        if (hasIconType) {
            VersionIconType parsed = parseIconType(iconTypeRaw);
            if (parsed == null) {
                return ToolResult.failure("Unknown icon type '" + iconTypeRaw + "'. Valid values: " + builtinIconNames()
                        + ", or 'auto' to reset to automatic detection.");
            }
            targetType = parsed;
            customImage = null;
        } else {
            Path path;
            try {
                path = Paths.get(imagePathRaw);
            } catch (InvalidPathException e) {
                return ToolResult.failure("Invalid image path '" + imagePathRaw + "': " + e.getMessage());
            }
            if (!Files.isRegularFile(path)) {
                return ToolResult.failure("Image file not found: " + path + " (it must be an existing file).");
            }
            String ext = FileUtils.getExtension(path).toLowerCase(Locale.ROOT);
            if (!FXUtils.IMAGE_EXTENSIONS.contains(ext)) {
                return ToolResult.failure("Unsupported image type '" + (ext.isEmpty() ? "(none)" : "." + ext)
                        + "'. Supported image types: " + String.join(", ", FXUtils.IMAGE_EXTENSIONS) + ".");
            }
            targetType = null;
            customImage = path.toAbsolutePath();
        }

        // No-op fast paths (mirror the isolation tool's "already X; nothing to do"). A custom image is
        // always (re)applied since we cannot cheaply compare file contents.
        if (targetType != null) {
            if (targetType == VersionIconType.DEFAULT && currentType == VersionIconType.DEFAULT
                    && currentCustomFile.isEmpty()) {
                return ToolResult.success("Instance '" + instance + "' already uses automatic icon detection; "
                        + "nothing to do.");
            }
            if (targetType != VersionIconType.DEFAULT && targetType == currentType) {
                return ToolResult.success("Instance '" + instance + "' already uses the built-in '"
                        + typeName(targetType) + "' icon; nothing to do.");
            }
        }

        if (repository.isInstanceGameSettingsReadOnly(instance)) {
            return ToolResult.failure("The game settings of instance '" + instance + "' are read-only and cannot be "
                    + "modified. It currently uses " + currentDesc + ".");
        }

        // Mutate on the JavaFX thread: the icon property carries the UI's auto-save listener and the
        // onVersionIconChanged event drives JavaFX UI updates (the instance list re-reads its icon).
        AtomicReference<ToolResult> result = new AtomicReference<>();
        Runnable mutate = () -> {
            try {
                GameSettings.Instance setting = repository.getInstanceGameSettingsOrCreate(instance);
                if (setting == null) {
                    result.set(ToolResult.failure("Could not obtain a writable game-settings object for instance '"
                            + instance + "'."));
                    return;
                }

                final String message;
                if (customImage != null) {
                    // Custom image: copy the file (HMCL replaces any previous icon.* and re-validates the
                    // extension) and leave the type at DEFAULT so getVersionIconImage renders the file.
                    repository.setVersionIconFile(instance, customImage);
                    setting.iconProperty().setValue(VersionIconType.DEFAULT);
                    message = "Set the icon of instance '" + instance + "' to the custom image '"
                            + customImage.getFileName() + "' (copied into the instance folder). Previous icon: "
                            + currentDesc + ".";
                } else if (targetType == VersionIconType.DEFAULT) {
                    // Reset to automatic: drop any custom icon file so DEFAULT means auto-detection, not a
                    // leftover image.
                    repository.deleteIconFile(instance);
                    setting.iconProperty().setValue(VersionIconType.DEFAULT);
                    message = "Reset the icon of instance '" + instance + "' to automatic detection "
                            + "(from the loader / game version). Previous icon: " + currentDesc + ".";
                } else {
                    // Built-in icon: the exact single mutation the native dialog performs.
                    setting.iconProperty().setValue(targetType);
                    message = "Set the icon of instance '" + instance + "' to the built-in '" + typeName(targetType)
                            + "' icon. Previous icon: " + currentDesc + ".";
                }

                repository.saveGameSettings(instance); // explicit save in addition to the auto-save listener
                repository.onVersionIconChanged.fireEvent(new Event(this));

                result.set(ToolResult.success(message + "\nThe launcher's instance list updates immediately."));
            } catch (Throwable e) {
                result.set(ToolResult.failure("Failed to set the icon of '" + instance + "': " + e.getMessage()));
            }
        };

        if (Platform.isFxApplicationThread()) {
            mutate.run();
        } else {
            CountDownLatch latch = new CountDownLatch(1);
            try {
                Platform.runLater(() -> {
                    try {
                        mutate.run();
                    } finally {
                        latch.countDown();
                    }
                });
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    return ToolResult.failure("Timed out while applying the icon; the UI did not respond.");
                }
            } catch (IllegalStateException e) {
                // JavaFX runtime not started (should not happen inside the running launcher UI).
                return ToolResult.failure("Cannot apply the icon: the JavaFX runtime is unavailable.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.failure("Interrupted while applying the icon.");
            }
        }

        ToolResult finalResult = result.get();
        return finalResult != null ? finalResult
                : ToolResult.failure("Unknown error while setting the icon of '" + instance + "'.");
    }

    /// Describes an instance's current icon for the read-only report and confirmation messages.
    private static String describeIcon(VersionIconType type, Optional<Path> customFile) {
        if (type == VersionIconType.DEFAULT) {
            if (customFile.isPresent()) {
                return "a custom image (" + customFile.get().getFileName() + ")";
            }
            return "automatic detection (auto-detected from the loader / game version)";
        }
        return "the built-in '" + typeName(type) + "' icon";
    }

    /// Parses a requested icon type, case-insensitively, tolerating hyphens/spaces and a few friendly
    /// aliases. Returns {@code null} for an unrecognised value. "auto"/"automatic"/"none"/"reset"/
    /// "default" all map to {@link VersionIconType#DEFAULT} (automatic detection).
    @Nullable
    private static VersionIconType parseIconType(String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        switch (normalized) {
            case "AUTO":
            case "AUTOMATIC":
            case "NONE":
            case "RESET":
            case "DETECT":
            case "DEFAULT":
                return VersionIconType.DEFAULT;
            case "NEOFORGE":
                return VersionIconType.NEO_FORGE;
            case "LEGACYFABRIC":
                return VersionIconType.LEGACY_FABRIC;
            case "CRAFTTABLE":
            case "CRAFTING_TABLE":
            case "WORKBENCH":
            case "CRAFT":
                return VersionIconType.CRAFT_TABLE;
            case "APRILFOOLS":
            case "APRIL":
                return VersionIconType.APRIL_FOOLS;
            default:
                try {
                    return VersionIconType.valueOf(normalized);
                } catch (IllegalArgumentException e) {
                    return null;
                }
        }
    }

    private static String typeName(VersionIconType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    /// Comma-joined lower-case names of the selectable built-in icons (every {@link VersionIconType}
    /// except DEFAULT, which is exposed as the 'auto' reset instead).
    private static String builtinIconNames() {
        return java.util.Arrays.stream(VersionIconType.values())
                .filter(type -> type != VersionIconType.DEFAULT)
                .map(SetInstanceIconTool::typeName)
                .collect(Collectors.joining(", "));
    }
}
