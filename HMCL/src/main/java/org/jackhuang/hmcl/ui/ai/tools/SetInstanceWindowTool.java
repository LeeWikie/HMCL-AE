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
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.setting.GameWindowType;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// Sets the initial game-window mode — `windowed` / `fullscreen` / `maximized` — and, for the
/// `windowed` mode only, the window's pixel width/height for a single Minecraft instance of the
/// selected profile.
///
/// Mirrors the exact mutation HMCL's native settings UI performs
/// ({@code GameSettingsPage}'s window-type radio + windowed-resolution combo box):
/// - window mode: `instance.windowTypeProperty().setValue(type)` +
///   `instance.getOverrideProperties().add(GameSettings.PROPERTY_WINDOW_TYPE)`;
/// - windowed resolution: `instance.widthProperty().setValue(w)` /
///   `instance.heightProperty().setValue(h)` +
///   `add(GameSettings.PROPERTY_WIDTH)` / `add(GameSettings.PROPERTY_HEIGHT)` — width and height
///   are a coupled pair in the UI ({@code setWindowSizeOverridden}), so this tool always overrides
///   them together.
///
/// The `overrideProperties` registration is the load-bearing part: an effective (launch-time)
/// setting is only read from THIS instance when its property name is also listed in
/// `overrideProperties` (see {@link GameSettings.Effective}); writing `windowType`/`width`/`height`
/// without it would persist to disk yet be silently ignored at launch whenever the parent preset
/// differs — the exact silent no-op a dedicated tool exists to prevent.
///
/// Width/height only take effect in `windowed` mode (fullscreen/maximized ignore them at launch),
/// so this tool rejects a width/height paired with a non-windowed target instead of writing a
/// setting that would never be observed.
///
/// The mutation is performed on the JavaFX thread (the settings properties are bound to the UI and
/// carry an auto-save listener). Instances whose settings file is read-only cannot be changed and
/// are reported instead.
///
/// Permission level: it WRITES the instance's HMCL game-settings file.
@NotNullByDefault
public final class SetInstanceWindowTool implements Tool {

    /// Sanity ceiling for a window dimension in pixels — comfortably past 16K displays, just high
    /// enough to reject a fat-fingered value (e.g. 19201080 for "1920x1080") rather than write it.
    private static final int MAX_DIMENSION = 16384;

    @Override
    public String getName() {
        return "set_instance_window";
    }

    @Override
    public String getDescription() {
        return "Sets the initial game-window mode (windowed/fullscreen/maximized) and, for windowed mode only, "
                + "the window's pixel width/height for a Minecraft instance of the selected profile. "
                + "Parameters: windowType (optional: 'windowed', 'fullscreen', or 'maximized'), "
                + "width and height (optional positive integers in pixels; ONLY valid together with windowed mode), "
                + "instance (optional, the instance id; defaults to the currently selected instance). "
                + "WRITES the instance's HMCL game-settings file. "
                + "If windowType, width and height are all omitted it only REPORTS the current window settings. "
                + "Width/height are ignored by the game in fullscreen/maximized mode, so passing them with a "
                + "non-windowed windowType is rejected.";
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

        // Do NOT accept generic aliases: the instance resolver must not steal a 'query' that is
        // actually meant for the windowType value below.
        InstanceToolSupport.ResolvedInstance target =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (target.failure() != null) {
            return target.failure();
        }
        final String instance = target.name();

        // ---- Read the current effective window settings (also the read-only report) ----
        GameWindowType currentType;
        int currentWidth;
        int currentHeight;
        try {
            GameSettings.Effective effective = repository.getEffectiveGameSettings(instance);
            currentType = effective.getInheritable(GameSettings::windowTypeProperty);
            currentWidth = effective.getWidth();
            currentHeight = effective.getHeight();
        } catch (Throwable e) {
            return ToolResult.failure("Failed to read the window settings of '" + instance + "': " + e.getMessage());
        }
        GameSettings.Instance existing = repository.getInstanceGameSettings(instance);
        boolean typeOverridden = existing != null
                && existing.getOverrideProperties().contains(GameSettings.PROPERTY_WINDOW_TYPE);
        boolean sizeOverridden = existing != null
                && (existing.getOverrideProperties().contains(GameSettings.PROPERTY_WIDTH)
                || existing.getOverrideProperties().contains(GameSettings.PROPERTY_HEIGHT));

        // windowType honours the generic 'query' alias (e.g. instance(action="set_window", query="fullscreen")).
        Object typeObj = parameters.get("windowType");
        if (typeObj == null) {
            typeObj = parameters.get("query");
        }
        boolean typeGiven = typeObj != null && !String.valueOf(typeObj).trim().isEmpty();
        boolean widthGiven = parameters.get("width") != null
                && !String.valueOf(parameters.get("width")).trim().isEmpty();
        boolean heightGiven = parameters.get("height") != null
                && !String.valueOf(parameters.get("height")).trim().isEmpty();

        // No value supplied at all: degrade to a read-only report.
        if (!typeGiven && !widthGiven && !heightGiven) {
            return ToolResult.success(describeCurrent(instance, currentType, currentWidth, currentHeight,
                    typeOverridden, sizeOverridden)
                    + "\nTo change it, call this tool again with 'windowType' (windowed/fullscreen/maximized) "
                    + "and, for windowed mode, 'width'/'height'.");
        }

        // ---- Parse & validate windowType ----
        GameWindowType newType = null;
        if (typeGiven) {
            newType = parseWindowType(String.valueOf(typeObj));
            if (newType == null) {
                return ToolResult.failure("Parameter 'windowType' must be one of: windowed, fullscreen, maximized "
                        + "(got: " + typeObj + ").");
            }
        }

        // ---- Parse & validate width/height ----
        // The two are a coupled pair (matching the native UI, which overrides both together). When
        // only one is supplied, the other is kept from the current effective size so a "make it
        // 1920 wide" style request needs only the changed dimension.
        Integer newWidth = null;
        Integer newHeight = null;
        if (widthGiven || heightGiven) {
            if (widthGiven) {
                int w = InstanceToolSupport.parseInt(parameters.get("width"), Integer.MIN_VALUE);
                ToolResult err = validateDimension("width", parameters.get("width"), w);
                if (err != null) {
                    return err;
                }
                newWidth = w;
            }
            if (heightGiven) {
                int h = InstanceToolSupport.parseInt(parameters.get("height"), Integer.MIN_VALUE);
                ToolResult err = validateDimension("height", parameters.get("height"), h);
                if (err != null) {
                    return err;
                }
                newHeight = h;
            }
            // Fill the missing dimension from the current effective size.
            if (newWidth == null) {
                newWidth = currentWidth;
            }
            if (newHeight == null) {
                newHeight = currentHeight;
            }
            if (newWidth <= 0 || newHeight <= 0) {
                return ToolResult.failure("The current window size is unspecified (auto), so 'width' and 'height' "
                        + "must be supplied together. Provide both as positive integers.");
            }

            // Width/height are only honoured by the game in windowed mode. The mode this call will
            // leave in effect is the newly-requested type, or — when windowType is omitted — the
            // current effective type.
            GameWindowType effectiveTargetType = typeGiven ? newType : currentType;
            if (effectiveTargetType != GameWindowType.WINDOWED) {
                return ToolResult.failure("width/height only take effect in windowed mode, but the window mode "
                        + (typeGiven ? "requested" : "currently in effect") + " is "
                        + effectiveTargetType.name().toLowerCase(Locale.ROOT) + ". "
                        + "Pass windowType=\"windowed\" together with width/height, or omit width/height.");
            }
        }

        if (repository.isInstanceGameSettingsReadOnly(instance)) {
            return ToolResult.failure("The game settings of instance '" + instance + "' are read-only and cannot "
                    + "be modified. " + describeCurrent(instance, currentType, currentWidth, currentHeight,
                    typeOverridden, sizeOverridden));
        }

        final GameWindowType finalType = newType;
        final Integer finalWidth = newWidth;
        final Integer finalHeight = newHeight;

        // Mutate on the JavaFX thread: the settings properties may be bound to the UI and carry an
        // auto-save listener that runs on whichever thread changes the property.
        AtomicReference<ToolResult> result = new AtomicReference<>();
        Runnable task = () -> {
            try {
                GameSettings.Instance setting = repository.getInstanceGameSettingsOrCreate(instance);
                if (setting == null) {
                    result.set(ToolResult.failure("Could not obtain a writable game-settings object for instance '"
                            + instance + "'."));
                    return;
                }
                StringBuilder changes = new StringBuilder();
                if (finalType != null) {
                    setting.windowTypeProperty().setValue(finalType);
                    setting.getOverrideProperties().add(GameSettings.PROPERTY_WINDOW_TYPE);
                    changes.append("\n  window mode: ").append(finalType.name().toLowerCase(Locale.ROOT));
                }
                if (finalWidth != null && finalHeight != null) {
                    setting.widthProperty().setValue((double) (int) finalWidth);
                    setting.heightProperty().setValue((double) (int) finalHeight);
                    setting.getOverrideProperties().add(GameSettings.PROPERTY_WIDTH);
                    setting.getOverrideProperties().add(GameSettings.PROPERTY_HEIGHT);
                    changes.append("\n  window size: ").append(finalWidth).append('x').append(finalHeight);
                }
                repository.saveGameSettings(instance); // explicit save in addition to the auto-save listener
                result.set(ToolResult.success("Updated the window settings of instance '" + instance + "':"
                        + changes
                        + "\nThe change takes effect the next time the instance is launched."));
            } catch (Throwable e) {
                result.set(ToolResult.failure("Failed to set the window settings for '" + instance + "': " + e.getMessage()));
            }
        };

        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            CountDownLatch latch = new CountDownLatch(1);
            try {
                Platform.runLater(() -> {
                    try {
                        task.run();
                    } finally {
                        latch.countDown();
                    }
                });
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    return ToolResult.failure("Timed out while applying the window settings; the UI did not respond.");
                }
            } catch (IllegalStateException e) {
                return ToolResult.failure("Cannot apply the window settings: the JavaFX runtime is unavailable.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.failure("Interrupted while applying the window settings.");
            }
        }

        ToolResult finalResult = result.get();
        return finalResult != null ? finalResult
                : ToolResult.failure("Unknown error while setting the window settings for '" + instance + "'.");
    }

    /// Builds the human-readable "current state" line shared by the report and the read-only /
    /// error paths.
    private static String describeCurrent(String instance, GameWindowType type, int width, int height,
                                          boolean typeOverridden, boolean sizeOverridden) {
        StringBuilder sb = new StringBuilder("Instance '").append(instance).append("' window mode is ")
                .append(type.name().toLowerCase(Locale.ROOT))
                .append(typeOverridden ? " (overridden on this instance)" : " (inherited from the parent preset)")
                .append('.');
        if (type == GameWindowType.WINDOWED) {
            sb.append(" Windowed size: ");
            if (width > 0 && height > 0) {
                sb.append(width).append('x').append(height);
            } else {
                sb.append("auto (unspecified)");
            }
            sb.append(sizeOverridden ? " (overridden on this instance)." : " (inherited from the parent preset).");
        } else {
            sb.append(" Width/height do not apply in ").append(type.name().toLowerCase(Locale.ROOT)).append(" mode.");
        }
        return sb.toString();
    }

    /// Validates a single window dimension parsed from {@code raw} into {@code value}. Returns a
    /// failure {@link ToolResult} to short-circuit on, or {@code null} when the value is acceptable.
    @Nullable
    private static ToolResult validateDimension(String name, @Nullable Object raw, int value) {
        if (value == Integer.MIN_VALUE) {
            return ToolResult.failure("Parameter '" + name + "' must be a positive integer number of pixels, got: " + raw);
        }
        if (value <= 0) {
            return ToolResult.failure("Parameter '" + name + "' must be a positive integer (in pixels), got: " + value);
        }
        if (value > MAX_DIMENSION) {
            return ToolResult.failure("Parameter '" + name + "' (" + value + ") is unreasonably large; "
                    + "use a value between 1 and " + MAX_DIMENSION + " pixels.");
        }
        return null;
    }

    /// Parses a window-type token (case-insensitive, trimmed) into a {@link GameWindowType}, or
    /// {@code null} when unrecognized.
    @Nullable
    private static GameWindowType parseWindowType(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "windowed", "window" -> GameWindowType.WINDOWED;
            case "fullscreen", "full_screen", "full-screen", "full" -> GameWindowType.FULLSCREEN;
            case "maximized", "maximised", "maximize", "max" -> GameWindowType.MAXIMIZED;
            default -> null;
        };
    }
}
