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
import org.jackhuang.hmcl.java.JavaManager;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.setting.JavaVersionType;
import org.jackhuang.hmcl.setting.Profile;
import org.jackhuang.hmcl.setting.Profiles;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// A tool that selects the Java runtime used to launch a single Minecraft instance of the
/// selected profile — the AI-facing equivalent of the "Java" radio group in HMCL's native
/// game-settings page ([`GameSettingsPage`]).
///
/// It drives the exact same per-instance override path HMCL's own UI uses
/// ([`IndependentSettingBinder`] / `GameSettingsPage`'s Java selector), reusing:
/// - [`HMCLGameRepository#getEffectiveGameSettings(String)`] to read the current effective selection,
/// - [`HMCLGameRepository#getInstanceGameSettingsOrCreate(String)`] for the writable instance override,
/// - [`GameSettings#javaTypeProperty()`] plus the mode-specific payload property
///   (`customJavaVersion` / `customJavaPath` / `detectedJava`),
/// - [`HMCLGameRepository#saveGameSettings(String)`] to persist it.
///
/// The four modes mirror [`JavaVersionType`] and the four branches of
/// [`GameSettings.Effective#getJava`]:
/// - `auto`    → [`JavaVersionType#AUTO`]: HMCL picks a suitable Java for the game version;
/// - `version` → [`JavaVersionType#VERSION`]: pin an installed Java by MAJOR version (e.g. 17, 21),
///   stored in `customJavaVersion`;
/// - `detected`→ [`JavaVersionType#DETECTED`]: reference one runtime from HMCL's detected-Java list
///   by its executable path. The path is matched against [`JavaManager#getAllJava()`] and the stable
///   reference is built with [`GameSettings.DetectedJava#of`], which computes the SHA-256 path
///   fingerprint internally — the model NEVER hashes anything by hand;
/// - `custom`  → [`JavaVersionType#CUSTOM`]: use an explicit Java executable path, stored in
///   `customJavaPath`.
///
/// Correctness note (the same "overrideProperties gotcha" [`SetInstanceMemoryTool`] documents):
/// each written property's name is also added to the instance's `overrideProperties`, otherwise the
/// effective selection keeps inheriting the parent preset and the write is a silent no-op at launch
/// time (see [`GameSettings.Effective#getJava`] / `isOverridden`). For `detected` both `javaType`
/// and `detectedJava` are marked overridden; for `version` both `javaType` and `customJavaVersion`;
/// for `custom` both `javaType` and `customJavaPath`; `auto` marks `javaType` only — exactly what
/// `GameSettingsPage`'s selector listener does.
///
/// The mutation is performed on the JavaFX thread (the settings properties are bound to the UI and
/// carry an auto-save listener). Instances whose settings file is read-only cannot be changed and
/// are reported instead. When no `mode` is supplied the tool only REPORTS the current selection and
/// the effective resolved executable.
///
/// Permission level: it WRITES the instance's HMCL game-settings file.
@NotNullByDefault
public final class SetInstanceJavaTool implements Tool {

    @Override
    public String getName() {
        return "set_instance_java";
    }

    @Override
    public String getDescription() {
        return "Selects which Java runtime launches a Minecraft instance of the selected profile (the same "
                + "choice as the native settings page's Java radio group). Parameters: mode (auto|version|"
                + "detected|custom), plus one payload depending on mode — version (mode=version: the Java MAJOR "
                + "version as an integer, e.g. 8/17/21), path (mode=custom: absolute path to a Java executable; "
                + "mode=detected: the executable path of a Java HMCL has already detected — use list_java to see "
                + "them). instance (optional, the instance id; defaults to the currently selected instance). "
                + "For mode=detected the path is matched against HMCL's detected-Java list and the reference's "
                + "path fingerprint is computed internally — never hash anything yourself. WRITES the instance's "
                + "HMCL game-settings file. If mode is omitted it only REPORTS the current Java selection and the "
                + "effective resolved executable. Note: this controls which Java HMCL launches with, not any "
                + "in-game setting.";
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

        // NOTE: generic aliases are NOT accepted: 'mode'/'version'/'path' are distinct parameters and
        // must not be stolen by the instance resolver's generic 'query'/'version' fallback.
        InstanceToolSupport.ResolvedInstance target =
                InstanceToolSupport.resolveInstance(repository, parameters, false);
        if (target.failure() != null) {
            return target.failure();
        }
        final String instance = target.name();

        // Read the current effective Java selection (used for the report path and for the
        // "previous value" line of a write).
        final JavaVersionType currentType;
        final String currentCustomVersion;
        final String currentCustomPath;
        final GameSettings.@Nullable DetectedJava currentDetected;
        final boolean javaTypeOverridden;
        try {
            GameSettings.Effective eff = repository.getEffectiveGameSettings(instance);
            currentType = eff.getInheritable(GameSettings::javaTypeProperty);
            currentCustomVersion = orEmpty(eff.getInheritable(GameSettings::customJavaVersionProperty));
            currentCustomPath = orEmpty(eff.getInheritable(GameSettings::customJavaPathProperty));
            currentDetected = eff.getInheritable(GameSettings::detectedJavaProperty);
            GameSettings.Instance existing = repository.getInstanceGameSettings(instance);
            javaTypeOverridden = existing != null
                    && existing.getOverrideProperties().contains(GameSettings.PROPERTY_JAVA_TYPE);
        } catch (Throwable e) {
            return ToolResult.failure("Failed to read the Java setting of '" + instance + "': " + e.getMessage());
        }

        // No mode supplied: degrade to a read-only report.
        Object modeObj = parameters.get("mode");
        if (modeObj == null || modeObj.toString().trim().isEmpty()) {
            return report(repository, instance, currentType, currentCustomVersion, currentCustomPath,
                    currentDetected, javaTypeOverridden);
        }

        String modeStr = modeObj.toString().trim().toLowerCase(Locale.ROOT);
        final JavaVersionType mode = switch (modeStr) {
            case "auto" -> JavaVersionType.AUTO;
            case "version" -> JavaVersionType.VERSION;
            case "detected" -> JavaVersionType.DETECTED;
            case "custom" -> JavaVersionType.CUSTOM;
            default -> null;
        };
        if (mode == null) {
            return ToolResult.failure("Parameter 'mode' must be one of auto|version|detected|custom, got: " + modeObj);
        }

        // Per-mode validation, resolving the payload that will be written. Any I/O / Java-list lookup
        // is done HERE (off the FX thread) so the FX task stays a pure property mutation and every
        // validation error is returned without a UI round-trip.
        final int majorVersion;       // mode=version
        final String customPath;      // mode=custom
        final GameSettings.@Nullable DetectedJava detectedToWrite; // mode=detected
        final String detectedLabel;   // mode=detected, for the success message
        String advisory = "";

        switch (mode) {
            case VERSION -> {
                Object versionObj = parameters.get("version");
                if (versionObj == null || versionObj.toString().trim().isEmpty()) {
                    return ToolResult.failure("mode=version requires a 'version' parameter: the Java MAJOR version "
                            + "as an integer (e.g. 8, 17, 21).");
                }
                int v = InstanceToolSupport.parseInt(versionObj, Integer.MIN_VALUE);
                if (v == Integer.MIN_VALUE || v <= 0) {
                    return ToolResult.failure("mode=version 'version' must be a positive integer major version "
                            + "(e.g. 8, 17, 21), got: " + versionObj);
                }
                majorVersion = v;
                customPath = "";
                detectedToWrite = null;
                detectedLabel = "";
                try {
                    boolean anyMatch = false;
                    for (JavaRuntime jr : JavaManager.getAllJava()) {
                        if (jr.getParsedVersion() == v) {
                            anyMatch = true;
                            break;
                        }
                    }
                    if (!anyMatch) {
                        advisory = "\nNote: no currently-detected Java runtime reports major version " + v
                                + "; the game will fail to launch until a matching Java is installed or downloaded"
                                + " (e.g. instance(action=\"download_java\", javaVersion=" + v + ")).";
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolResult.failure("Interrupted while reading Java runtimes.");
                } catch (Throwable ignored) {
                    // Advisory is best-effort; never fail the write because the Java list could not be read.
                }
            }
            case CUSTOM -> {
                String p = InstanceToolSupport.string(parameters, "path");
                if (p == null) {
                    return ToolResult.failure("mode=custom requires a 'path' parameter: the absolute path to a Java "
                            + "executable (e.g. .../bin/java or .../bin/java.exe).");
                }
                Path exe;
                try {
                    exe = Path.of(p);
                } catch (InvalidPathException e) {
                    return ToolResult.failure("mode=custom 'path' is not a valid filesystem path: " + p);
                }
                if (!Files.isRegularFile(exe)) {
                    return ToolResult.failure("mode=custom 'path' does not point to an existing file: " + p
                            + ". Pass the Java executable itself (e.g. .../bin/java or .../bin/java.exe).");
                }
                majorVersion = -1;
                customPath = p;
                detectedToWrite = null;
                detectedLabel = "";
            }
            case DETECTED -> {
                String p = InstanceToolSupport.string(parameters, "path");
                if (p == null) {
                    return ToolResult.failure("mode=detected requires a 'path' parameter: the executable path of a "
                            + "Java runtime HMCL has already detected (see instance(action=\"list_java\")).");
                }
                DetectedResolution resolution = resolveDetectedRuntime(p);
                if (resolution.failure() != null) {
                    return resolution.failure();
                }
                JavaRuntime matched = resolution.runtime();
                detectedToWrite = GameSettings.DetectedJava.of(matched);
                detectedLabel = "Java " + matched.getVersion() + " at " + matched.getBinary();
                majorVersion = -1;
                customPath = "";
            }
            default -> { // AUTO
                majorVersion = -1;
                customPath = "";
                detectedToWrite = null;
                detectedLabel = "";
            }
        }

        if (repository.isInstanceGameSettingsReadOnly(instance)) {
            return ToolResult.failure("The game settings of instance '" + instance + "' are read-only and cannot be "
                    + "modified. Its current Java selection is "
                    + describe(currentType, currentCustomVersion, currentCustomPath, currentDetected) + ".");
        }

        final String previousDesc =
                describe(currentType, currentCustomVersion, currentCustomPath, currentDetected);
        final String newDesc = switch (mode) {
            case AUTO -> "automatic (HMCL picks a suitable Java for the game version)";
            case VERSION -> "major version " + majorVersion;
            case CUSTOM -> "custom executable " + customPath;
            case DETECTED -> "detected runtime " + detectedLabel;
        };
        final String advisoryFinal = advisory;

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

                // Each mutated property's name MUST also be added to overrideProperties, or the
                // effective selection keeps inheriting the parent preset and this write becomes a
                // silent launch-time no-op — the exact override contract GameSettingsPage's Java
                // selector follows (setPropertyOverridden + setValue, paired).
                switch (mode) {
                    case AUTO -> {
                        setting.javaTypeProperty().setValue(JavaVersionType.AUTO);
                        setting.getOverrideProperties().add(GameSettings.PROPERTY_JAVA_TYPE);
                    }
                    case VERSION -> {
                        setting.javaTypeProperty().setValue(JavaVersionType.VERSION);
                        setting.customJavaVersionProperty().setValue(Integer.toString(majorVersion));
                        setting.getOverrideProperties().add(GameSettings.PROPERTY_JAVA_TYPE);
                        setting.getOverrideProperties().add(GameSettings.PROPERTY_CUSTOM_JAVA_VERSION);
                    }
                    case CUSTOM -> {
                        setting.javaTypeProperty().setValue(JavaVersionType.CUSTOM);
                        setting.customJavaPathProperty().setValue(customPath);
                        setting.getOverrideProperties().add(GameSettings.PROPERTY_JAVA_TYPE);
                        setting.getOverrideProperties().add(GameSettings.PROPERTY_CUSTOM_JAVA_PATH);
                    }
                    case DETECTED -> {
                        setting.javaTypeProperty().setValue(JavaVersionType.DETECTED);
                        setting.detectedJavaProperty().setValue(detectedToWrite);
                        setting.getOverrideProperties().add(GameSettings.PROPERTY_JAVA_TYPE);
                        setting.getOverrideProperties().add(GameSettings.PROPERTY_DETECTED_JAVA);
                    }
                }
                repository.saveGameSettings(instance); // explicit save in addition to the auto-save listener

                result.set(ToolResult.success("Set the Java selection of instance '" + instance + "' to "
                        + newDesc + ". Previous: " + previousDesc + "." + advisoryFinal
                        + "\nThe change takes effect the next time the instance is launched."));
            } catch (Throwable e) {
                result.set(ToolResult.failure("Failed to set the Java selection for '" + instance + "': "
                        + e.getMessage()));
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
                    return ToolResult.failure("Timed out while applying the Java setting; the UI did not respond.");
                }
            } catch (IllegalStateException e) {
                // JavaFX runtime not started (should not happen inside the running launcher UI).
                return ToolResult.failure("Cannot apply the Java setting: the JavaFX runtime is unavailable.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.failure("Interrupted while applying the Java setting.");
            }
        }

        ToolResult finalResult = result.get();
        return finalResult != null ? finalResult
                : ToolResult.failure("Unknown error while setting the Java selection for '" + instance + "'.");
    }

    /// Builds the read-only report of the current effective Java selection.
    private static ToolResult report(HMCLGameRepository repository,
                                     String instance,
                                     JavaVersionType currentType,
                                     String currentCustomVersion,
                                     String currentCustomPath,
                                     GameSettings.@Nullable DetectedJava currentDetected,
                                     boolean javaTypeOverridden) {
        StringBuilder sb = new StringBuilder();
        sb.append("Instance '").append(instance).append("' Java selection:\n");
        sb.append("  mode: ").append(currentType)
                .append(javaTypeOverridden ? " (set on this instance)" : " (inherited from the parent preset)")
                .append('\n');
        switch (currentType) {
            case VERSION -> sb.append("  requested Java major version: ")
                    .append(currentCustomVersion.isEmpty() ? "(unset — falls back to automatic)" : currentCustomVersion)
                    .append('\n');
            case CUSTOM -> sb.append("  custom executable path: ")
                    .append(currentCustomPath.isEmpty() ? "(unset)" : currentCustomPath).append('\n');
            case DETECTED -> sb.append("  detected runtime: ")
                    .append(currentDetected == null || currentDetected.isEmpty()
                            ? "(unset)"
                            : "Java " + currentDetected.version() + " (matched by path fingerprint)").append('\n');
            case AUTO -> sb.append("  (HMCL picks a suitable Java for the game version automatically)\n");
        }

        // Effective resolved executable — mirrors GameSettingsPage's subtitle resolution
        // (Effective#getJava(null, null)); guarded on the Java list being loaded so it never blocks.
        if (JavaManager.isInitialized()) {
            try {
                JavaRuntime resolved = repository.getEffectiveGameSettings(instance).getJava(null, null);
                sb.append("  effective java: ")
                        .append(resolved != null ? resolved.getBinary().toString()
                                : "(none resolved — no matching Java found)")
                        .append('\n');
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sb.append("  effective java: (interrupted while resolving)\n");
            } catch (Throwable e) {
                sb.append("  effective java: (could not resolve: ").append(e.getMessage()).append(")\n");
            }
        } else {
            sb.append("  effective java: (Java runtime list is still loading)\n");
        }

        sb.append("To change it, call this action again with a 'mode' parameter:\n")
                .append("  mode=auto | mode=version (+ version=<major int, e.g. 17>) | ")
                .append("mode=detected (+ path=<executable of a detected Java, see list_java>) | ")
                .append("mode=custom (+ path=<absolute path to a Java executable>).");
        return ToolResult.success(sb.toString());
    }

    /// Outcome of {@link #resolveDetectedRuntime}: exactly one of {@code runtime} / {@code failure} is set.
    private record DetectedResolution(@Nullable JavaRuntime runtime, @Nullable ToolResult failure) {
        static DetectedResolution ok(JavaRuntime runtime) {
            return new DetectedResolution(runtime, null);
        }

        static DetectedResolution fail(ToolResult failure) {
            return new DetectedResolution(null, failure);
        }
    }

    /// Matches an executable path against HMCL's detected-Java list. DETECTED mode is only valid for
    /// a runtime HMCL actually knows about, because at launch time [`GameSettings.Effective#getJava`]
    /// re-resolves it by scanning [`JavaManager#getAllJava()`]; a path HMCL has not detected would be
    /// stored but never found again, so it is rejected here with a clear pointer to `custom` mode.
    private static DetectedResolution resolveDetectedRuntime(String path) {
        Path exe;
        try {
            exe = Path.of(path);
        } catch (InvalidPathException e) {
            return DetectedResolution.fail(ToolResult.failure("mode=detected 'path' is not a valid filesystem path: "
                    + path));
        }

        Collection<JavaRuntime> all;
        try {
            all = JavaManager.getAllJava();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DetectedResolution.fail(ToolResult.failure("Interrupted while reading detected Java runtimes."));
        } catch (Throwable e) {
            return DetectedResolution.fail(ToolResult.failure("Failed to read detected Java runtimes: "
                    + e.getMessage()));
        }

        JavaRuntime matched = matchByPath(all, exe);
        if (matched == null) {
            return DetectedResolution.fail(detectedNotFoundMessage(path, all));
        }
        return DetectedResolution.ok(matched);
    }

    /// Finds the detected runtime whose binary is {@code exe}. Compares canonicalized real paths
    /// (each {@code JavaRuntime}'s binary is already a {@code toRealPath()} result), tolerating the
    /// caller passing a Java-home directory instead of the executable, and finally falling back to a
    /// literal string comparison when the path cannot be canonicalized.
    @Nullable
    private static JavaRuntime matchByPath(Collection<JavaRuntime> all, Path exe) {
        Path real = null;
        try {
            real = exe.toRealPath();
        } catch (IOException | InvalidPathException ignored) {
            // Not resolvable; fall through to the string comparison below.
        }

        if (real != null) {
            for (JavaRuntime jr : all) {
                if (jr.getBinary().equals(real)) {
                    return jr;
                }
            }
            // The caller may have passed a Java-home directory; try its bin/ executable.
            if (Files.isDirectory(real)) {
                try {
                    Path candidate = JavaManager.getExecutable(real).toRealPath();
                    for (JavaRuntime jr : all) {
                        if (jr.getBinary().equals(candidate)) {
                            return jr;
                        }
                    }
                } catch (IOException | InvalidPathException ignored) {
                    // give up on the directory interpretation
                }
            }
        }

        String literal = exe.toString();
        for (JavaRuntime jr : all) {
            if (jr.getBinary().toString().equals(literal)) {
                return jr;
            }
        }
        return null;
    }

    private static ToolResult detectedNotFoundMessage(String path, Collection<JavaRuntime> all) {
        StringBuilder sb = new StringBuilder();
        sb.append("No detected Java runtime matches the path '").append(path).append("'. ");
        if (all.isEmpty()) {
            sb.append("HMCL has not detected any Java runtimes yet.");
        } else {
            sb.append("HMCL's detected Java runtimes are:\n");
            int shown = 0;
            for (JavaRuntime jr : all) {
                if (shown == 10) {
                    sb.append("  ... (").append(all.size() - shown).append(" more; use instance(action=\"list_java\"))\n");
                    break;
                }
                sb.append("  - Java ").append(jr.getVersion()).append(": ").append(jr.getBinary()).append('\n');
                shown++;
            }
        }
        sb.append("Pass the exact executable path of one of these (see instance(action=\"list_java\")), or use "
                + "mode=custom to point directly at any Java executable that is not in HMCL's detected list.");
        return ToolResult.failure(sb.toString().trim());
    }

    private static String describe(JavaVersionType type,
                                   String customVersion,
                                   String customPath,
                                   GameSettings.@Nullable DetectedJava detected) {
        return switch (type) {
            case AUTO -> "automatic";
            case VERSION -> "major version " + (customVersion.isEmpty() ? "(unset)" : customVersion);
            case CUSTOM -> "custom executable " + (customPath.isEmpty() ? "(unset)" : customPath);
            case DETECTED -> "detected runtime "
                    + (detected == null || detected.isEmpty() ? "(unset)" : "Java " + detected.version());
        };
    }

    private static String orEmpty(@Nullable String value) {
        return value != null ? value : "";
    }
}
