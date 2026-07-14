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
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.game.GraphicsAPI;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.Renderer;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/// Sets a single Minecraft instance's graphics settings — the graphics backend
/// (`graphicsBackend`: default/opengl/vulkan) and the per-backend renderer driver
/// (`openGLRenderer` / `vulkanRenderer`) — mirroring exactly what HMCL's native settings UI
/// ({@code GameSettingsPage}'s "graphics" section) writes.
///
/// The renderer candidate list is NOT static: it is computed per platform/GPU by
/// {@link Renderer#getSupported(GraphicsAPI)} (Windows/macOS/Linux ICD probing plus
/// graphics-card vendor detection). This tool validates every requested renderer against that
/// live list and refuses an unsupported value, listing the ones that ARE available so the model
/// can pick a real one instead of persisting a driver the game can't load.
///
/// Correctness note (the "overrideProperties gotcha" the memory/isolation tools also handle):
/// `graphicsBackend`, `openGLRenderer` and `vulkanRenderer` are all *inheritable* properties, so
/// their launch-time effective value is only read from THIS instance when the corresponding
/// `PROPERTY_*` name is also present in `overrideProperties` (see
/// {@code GameSettings.Effective#getRenderer} / {@code inheritable} / {@code isOverridden}, and the
/// native binder's {@code setPropertyOverridden}). Writing the property WITHOUT registering the
/// override would persist the value to disk yet keep launching with the inherited preset value —
/// a silent no-op. Every write below therefore pairs `…Property().setValue(…)` with
/// `getOverrideProperties().add(PROPERTY_…)`, exactly like the inheritance-button code path in
/// {@code GameSettingsPage}. Passing the sentinel value `"inherit"` for a field instead REMOVES
/// that override (the analog of clicking the native inherit button), so the instance follows its
/// parent preset again.
///
/// The mutation runs on the JavaFX thread (the settings properties are bound to the UI and carry
/// an auto-save listener). Instances whose settings file is read-only are reported, not changed.
///
/// Permission level: it WRITES the instance's HMCL game-settings file.
@NotNullByDefault
public final class SetInstanceGraphicsTool implements Tool {

    /// Sentinel accepted for any of the three fields to DROP the per-instance override and follow
    /// the parent preset again (mirrors the native inherit button). Not a real GraphicsAPI/Renderer
    /// value, so it can never collide with one.
    private static final String INHERIT = "inherit";

    @Override
    public String getName() {
        return "set_instance_graphics";
    }

    @Override
    public String getDescription() {
        return "Sets a Minecraft instance's graphics settings for the selected profile: the graphics backend "
                + "and the renderer driver used for each backend. Parameters (all optional; supply the ones you "
                + "want to change): graphicsBackend (default/opengl/vulkan), openGLRenderer, vulkanRenderer, "
                + "instance (the instance id; defaults to the currently selected instance). "
                + "The renderer choices are hardware/OS specific — call this tool with NO change parameters first "
                + "to REPORT the current graphics settings AND the exact renderer names supported on THIS machine, "
                + "then pass one of those names. An unsupported renderer is rejected with the list of valid ones. "
                + "Pass the special value 'inherit' for any field to clear that per-instance override and follow "
                + "the preset again. WRITES the instance's HMCL game-settings file; the change takes effect on the "
                + "next launch. Note: openGLRenderer only applies when the backend is opengl, vulkanRenderer only "
                + "when the backend is vulkan.";
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

        // Generic aliases are NOT accepted: this tool has several named parameters, so the
        // instance resolver must not steal one of them via the 'query' fallback.
        InstanceToolSupport.ResolvedInstance target =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (target.failure() != null) {
            return target.failure();
        }
        final String instance = target.name();

        // Snapshot the current effective values up-front (used for both the read-only report and
        // the before -> after diff of a write). Reading resolved properties is side-effect free.
        GameSettings.Effective before;
        try {
            before = repository.getEffectiveGameSettings(instance);
        } catch (Throwable e) {
            return ToolResult.failure("Failed to read the graphics settings of '" + instance + "': " + e.getMessage());
        }

        // Which raw values were supplied (blank / absent -> not supplied).
        String backendRaw = InstanceToolSupport.string(parameters, "graphicsBackend");
        String openGLRaw = InstanceToolSupport.string(parameters, "openGLRenderer");
        String vulkanRaw = InstanceToolSupport.string(parameters, "vulkanRenderer");

        // No change requested: degrade to a read-only report of the current settings plus the
        // machine's supported-renderer lists (which the model needs before it can pick a value).
        if (backendRaw == null && openGLRaw == null && vulkanRaw == null) {
            return ToolResult.success(reportText(repository, instance, before));
        }

        // ---- Validate every supplied value BEFORE touching the settings object ----
        // Each field is decoded to one of: SKIP (not supplied), INHERIT (drop override), or a
        // concrete value to write.

        boolean setBackend = backendRaw != null;
        boolean backendInherit = false;
        GraphicsAPI backendValue = GraphicsAPI.DEFAULT;
        if (setBackend) {
            if (backendRaw.equalsIgnoreCase(INHERIT)) {
                backendInherit = true;
            } else {
                GraphicsAPI parsed = parseBackend(backendRaw);
                if (parsed == null) {
                    return ToolFailures.failure(
                            "Unknown graphicsBackend '" + backendRaw + "' (valid: default, opengl, vulkan, or 'inherit')",
                            ToolFailures.Retryable.YES,
                            "the value must be one of the three graphics APIs or the inherit sentinel",
                            "call graphicsBackend=default (game default), opengl, vulkan, or inherit to follow the preset");
                }
                backendValue = parsed;
            }
        }

        boolean setOpenGL = openGLRaw != null;
        boolean openGLInherit = false;
        Renderer openGLValue = Renderer.DEFAULT;
        if (setOpenGL) {
            if (openGLRaw.equalsIgnoreCase(INHERIT)) {
                openGLInherit = true;
            } else {
                Renderer parsed = findSupportedRenderer(GraphicsAPI.OPENGL, openGLRaw);
                if (parsed == null) {
                    return ToolFailures.failure(
                            "OpenGL renderer '" + openGLRaw + "' is not supported on this machine "
                                    + "(supported: " + rendererNames(GraphicsAPI.OPENGL) + ")",
                            ToolFailures.Retryable.YES,
                            "renderer availability depends on this OS/GPU and only the listed drivers can be loaded",
                            "pass one of the supported OpenGL renderer names above, or 'inherit' to follow the preset");
                }
                openGLValue = parsed;
            }
        }

        boolean setVulkan = vulkanRaw != null;
        boolean vulkanInherit = false;
        Renderer vulkanValue = Renderer.DEFAULT;
        if (setVulkan) {
            if (vulkanRaw.equalsIgnoreCase(INHERIT)) {
                vulkanInherit = true;
            } else {
                Renderer parsed = findSupportedRenderer(GraphicsAPI.VULKAN, vulkanRaw);
                if (parsed == null) {
                    return ToolFailures.failure(
                            "Vulkan renderer '" + vulkanRaw + "' is not supported on this machine "
                                    + "(supported: " + rendererNames(GraphicsAPI.VULKAN) + ")",
                            ToolFailures.Retryable.YES,
                            "renderer availability depends on this OS/GPU and only the listed drivers can be loaded",
                            "pass one of the supported Vulkan renderer names above, or 'inherit' to follow the preset");
                }
                vulkanValue = parsed;
            }
        }

        if (repository.isInstanceGameSettingsReadOnly(instance)) {
            return ToolResult.failure("The game settings of instance '" + instance + "' are read-only and cannot be "
                    + "modified.\n" + reportText(repository, instance, before));
        }

        // Snapshot the "before" strings now (the Effective wraps live property objects, so its
        // getters would otherwise reflect the post-mutation state).
        String backendBefore = backendName(before);
        String openGLBefore = rendererName(before.getInheritable(GameSettings::openGLRendererProperty));
        String vulkanBefore = rendererName(before.getInheritable(GameSettings::vulkanRendererProperty));
        String rendererBefore = rendererName(before.getRenderer());

        // ---- Apply on the JavaFX thread (settings properties are UI-bound + auto-saving) ----
        final boolean fSetBackend = setBackend, fBackendInherit = backendInherit;
        final GraphicsAPI fBackendValue = backendValue;
        final boolean fSetOpenGL = setOpenGL, fOpenGLInherit = openGLInherit;
        final Renderer fOpenGLValue = openGLValue;
        final boolean fSetVulkan = setVulkan, fVulkanInherit = vulkanInherit;
        final Renderer fVulkanValue = vulkanValue;

        AtomicReference<ToolResult> result = new AtomicReference<>();
        Runnable task = () -> {
            try {
                GameSettings.Instance setting = repository.getInstanceGameSettingsOrCreate(instance);
                if (setting == null) {
                    result.set(ToolResult.failure("Could not obtain a writable game-settings object for instance '"
                            + instance + "'."));
                    return;
                }

                if (fSetBackend) {
                    if (fBackendInherit) {
                        setting.getOverrideProperties().remove(GameSettings.PROPERTY_GRAPHICS_BACKEND);
                    } else {
                        setting.graphicsBackendProperty().setValue(fBackendValue);
                        setting.getOverrideProperties().add(GameSettings.PROPERTY_GRAPHICS_BACKEND);
                    }
                }
                if (fSetOpenGL) {
                    if (fOpenGLInherit) {
                        setting.getOverrideProperties().remove(GameSettings.PROPERTY_OPENGL_RENDERER);
                    } else {
                        setting.openGLRendererProperty().setValue(fOpenGLValue);
                        setting.getOverrideProperties().add(GameSettings.PROPERTY_OPENGL_RENDERER);
                    }
                }
                if (fSetVulkan) {
                    if (fVulkanInherit) {
                        setting.getOverrideProperties().remove(GameSettings.PROPERTY_VULKAN_RENDERER);
                    } else {
                        setting.vulkanRendererProperty().setValue(fVulkanValue);
                        setting.getOverrideProperties().add(GameSettings.PROPERTY_VULKAN_RENDERER);
                    }
                }

                repository.saveGameSettings(instance); // explicit save in addition to the auto-save listener

                // Re-resolve to get the post-mutation effective values for the diff report.
                GameSettings.Effective after = repository.getEffectiveGameSettings(instance);
                GraphicsAPI effectiveBackend = after.getInheritable(GameSettings::graphicsBackendProperty);

                StringBuilder sb = new StringBuilder();
                sb.append("Updated graphics settings for instance '").append(instance).append("':\n");
                if (fSetBackend) {
                    sb.append("  - Graphics backend : ").append(backendName(after))
                            .append(" (was ").append(backendBefore).append(")\n");
                }
                if (fSetOpenGL) {
                    sb.append("  - OpenGL renderer  : ")
                            .append(rendererName(after.getInheritable(GameSettings::openGLRendererProperty)))
                            .append(" (was ").append(openGLBefore).append(")\n");
                }
                if (fSetVulkan) {
                    sb.append("  - Vulkan renderer  : ")
                            .append(rendererName(after.getInheritable(GameSettings::vulkanRendererProperty)))
                            .append(" (was ").append(vulkanBefore).append(")\n");
                }
                sb.append("  - Renderer in use  : ").append(rendererName(after.getRenderer()))
                        .append(" (was ").append(rendererBefore).append(")\n");

                // Non-blocking advisory: a renderer that doesn't match the active backend is inert.
                if (fSetOpenGL && !fOpenGLInherit && effectiveBackend != GraphicsAPI.OPENGL) {
                    sb.append("Note: openGLRenderer only takes effect when graphicsBackend is opengl "
                            + "(currently ").append(effectiveBackend.name().toLowerCase(java.util.Locale.ROOT)).append(").\n");
                }
                if (fSetVulkan && !fVulkanInherit && effectiveBackend != GraphicsAPI.VULKAN) {
                    sb.append("Note: vulkanRenderer only takes effect when graphicsBackend is vulkan "
                            + "(currently ").append(effectiveBackend.name().toLowerCase(java.util.Locale.ROOT)).append(").\n");
                }

                sb.append("The change takes effect the next time the instance is launched.");
                result.set(ToolResult.success(sb.toString()));
            } catch (Throwable e) {
                result.set(ToolResult.failure("Failed to set graphics settings for '" + instance + "': " + e.getMessage()));
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
                    return ToolResult.failure("Timed out while applying the graphics settings; the UI did not respond.");
                }
            } catch (IllegalStateException e) {
                // JavaFX runtime not started (should not happen inside the running launcher UI).
                return ToolResult.failure("Cannot apply the graphics settings: the JavaFX runtime is unavailable.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.failure("Interrupted while applying the graphics settings.");
            }
        }

        ToolResult finalResult = result.get();
        return finalResult != null ? finalResult
                : ToolResult.failure("Unknown error while setting graphics settings for '" + instance + "'.");
    }

    /// Builds the read-only report: current effective backend/renderers with their override state,
    /// the renderer actually in use, and the machine's supported-renderer lists.
    private static String reportText(HMCLGameRepository repository, String instance, GameSettings.Effective effective) {
        @Nullable GameSettings.Instance settings;
        try {
            settings = repository.getInstanceGameSettings(instance);
        } catch (Throwable e) {
            settings = null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Instance '").append(instance).append("' graphics settings (effective values):\n");
        sb.append("  - Graphics backend : ").append(backendName(effective))
                .append(' ').append(overrideTag(settings, GameSettings.PROPERTY_GRAPHICS_BACKEND)).append('\n');
        sb.append("  - OpenGL renderer  : ")
                .append(rendererName(effective.getInheritable(GameSettings::openGLRendererProperty)))
                .append(' ').append(overrideTag(settings, GameSettings.PROPERTY_OPENGL_RENDERER)).append('\n');
        sb.append("  - Vulkan renderer  : ")
                .append(rendererName(effective.getInheritable(GameSettings::vulkanRendererProperty)))
                .append(' ').append(overrideTag(settings, GameSettings.PROPERTY_VULKAN_RENDERER)).append('\n');
        sb.append("  - Renderer in use  : ").append(rendererName(effective.getRenderer()))
                .append("   (the driver actually applied for the active backend)\n\n");
        sb.append("Renderers supported on this machine (pass one of these names):\n");
        sb.append("  - OpenGL: ").append(rendererNames(GraphicsAPI.OPENGL)).append('\n');
        sb.append("  - Vulkan: ").append(rendererNames(GraphicsAPI.VULKAN)).append("\n\n");
        sb.append("To change, call set_graphics again with graphicsBackend (default/opengl/vulkan), "
                + "openGLRenderer, and/or vulkanRenderer. Pass 'inherit' for any field to drop the "
                + "per-instance override and follow the preset again.");
        return sb.toString();
    }

    private static String overrideTag(@Nullable GameSettings.Instance settings, String property) {
        boolean overridden = settings != null && settings.getOverrideProperties().contains(property);
        return overridden ? "(overridden)" : "(inherited)";
    }

    private static String backendName(GameSettings.Effective effective) {
        return effective.getInheritable(GameSettings::graphicsBackendProperty).name();
    }

    private static String rendererName(Renderer renderer) {
        return renderer.name();
    }

    private static @Nullable GraphicsAPI parseBackend(String value) {
        for (GraphicsAPI api : GraphicsAPI.values()) {
            if (api.name().equalsIgnoreCase(value)) {
                return api;
            }
        }
        return null;
    }

    /// Resolves a renderer name against the machine's live supported list for the given API,
    /// matching case-insensitively on the canonical {@link Renderer#name()} (e.g. "DEFAULT",
    /// "ZINK", "NVIDIA_VULKAN"). Returns {@code null} for a typo or an unsupported/wrong-API driver
    /// — the caller turns that into a candidate-listing error.
    private static @Nullable Renderer findSupportedRenderer(GraphicsAPI api, String value) {
        for (Renderer renderer : Renderer.getSupported(api)) {
            if (renderer.name().equalsIgnoreCase(value)) {
                return renderer;
            }
        }
        return null;
    }

    private static String rendererNames(GraphicsAPI api) {
        List<Renderer> supported = Renderer.getSupported(api);
        return supported.stream().map(Renderer::name).collect(Collectors.joining(", "));
    }
}
