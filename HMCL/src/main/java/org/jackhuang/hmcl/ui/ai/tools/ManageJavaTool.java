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
import javafx.beans.value.ChangeListener;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.java.JavaManager;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.UnsupportedPlatformException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/// Manages HMCL's registry of discovered Java runtimes — the AI counterpart of the launcher's Java
/// management page — for operations that {@link ListJavaTool} (read-only listing) and
/// {@link DownloadJavaTool} (download a Mojang runtime) do not cover. The {@code operation} parameter
/// selects one, each delegating to the exact native {@link JavaManager} call so there is no second
/// registry implementation:
///
/// - `refresh` → {@link JavaManager#refresh()}: re-scan the machine for installed Java runtimes and
///   report the refreshed list. Non-destructive.
/// - `add` → {@link JavaManager#getAddJavaTask(Path)}: register a Java runtime the user already has
///   (given its {@code java}/{@code java.exe} executable, or a JDK/JRE home directory whose
///   {@code bin/java} is located automatically). Refuses a path that is not a compatible Java runtime.
///   Writes only HMCL's own settings — it never copies or modifies the user's JDK.
/// - `uninstall` → {@link JavaManager#getUninstallJavaTask(JavaRuntime)}: permanently delete an
///   HMCL-DOWNLOADED (managed) runtime from disk. Only runtimes HMCL itself downloaded can be removed
///   this way; a system- or user-added JDK is refused so the tool never deletes a JDK HMCL did not
///   install. The target is picked by {@code javaVersion} (its major version) and/or {@code path}
///   (a substring of its install path), and the match must be unique.
///
/// Called with no {@code operation}, it only REPORTS the currently known runtimes (like `list_java`).
///
/// Permission level: worst case DELETES a managed runtime from disk ({@code uninstall} → DANGEROUS);
/// {@code add} registers a runtime (CONTROLLED_WRITE); {@code refresh} and the no-op report are
/// effectively read-only.
@NotNullByDefault
public final class ManageJavaTool implements Tool {

    /// Registering a runtime probes {@code java -version} on the executable; a slow disk / first probe
    /// can take a few seconds. Uninstall deletes a directory tree. Give both a generous window.
    private static final int ADD_TIMEOUT_SECONDS = 60;
    private static final int UNINSTALL_TIMEOUT_SECONDS = 120;

    /// Upper bound on how long `refresh` waits for the background rescan to publish a new list before
    /// it reports whatever is currently known (the scan may still finish afterwards).
    private static final int REFRESH_WAIT_SECONDS = 12;

    @Override
    public String getName() {
        return "manage_java";
    }

    @Override
    public String getDescription() {
        return "Manages HMCL's registry of Java runtimes (complements list_java, which lists them, and download_java, "
                + "which downloads a Mojang runtime). Parameter 'operation' selects the action:\n"
                + "- refresh: re-scan this machine for installed Java runtimes and report the refreshed list. "
                + "Non-destructive.\n"
                + "- add: register a Java runtime you ALREADY have. Parameter 'path' (required): the absolute path to "
                + "the java/java.exe executable, OR to a JDK/JRE home directory (its bin/java is located automatically). "
                + "Only registers it with HMCL — it does not copy or modify your JDK. Rejects a path that is not a "
                + "compatible Java runtime.\n"
                + "- uninstall: DANGEROUS — permanently delete an HMCL-DOWNLOADED (managed) Java runtime from disk. "
                + "Identify it with 'javaVersion' (its major version, e.g. 21) and/or 'path' (a substring of its install "
                + "path); the match must be unique. Only runtimes HMCL itself downloaded can be removed — a system or "
                + "user-added JDK is refused (this tool never deletes a JDK HMCL did not install).\n"
                + "Call with no 'operation' to only REPORT the currently known runtimes (like list_java). "
                + "Reuses HMCL's native Java-management paths.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String op = str(parameters.get("operation"));
        if (op == null) {
            op = str(parameters.get("mode"));
        }
        String operation = op == null ? "" : op.toLowerCase(Locale.ROOT);

        return switch (operation) {
            case "", "list", "report" -> report();
            case "refresh", "rescan", "scan" -> refresh();
            case "add", "register", "import" -> add(parameters);
            case "uninstall", "remove", "delete" -> uninstall(parameters);
            default -> ToolFailures.failure(
                    "Unknown Java management operation '" + operation + "'",
                    ToolFailures.Retryable.YES,
                    "the operation is not one of the supported Java-management actions",
                    "use one of: refresh, add (with path), uninstall (with javaVersion and/or path) — or omit "
                            + "'operation' to only list the known runtimes");
        };
    }

    // ------------------------------------------------------------------
    // operation: report (no operation) — read-only
    // ------------------------------------------------------------------

    private ToolResult report() {
        List<JavaRuntime> runtimes = safeGetAll();
        if (runtimes == null) {
            return ToolResult.failure("Failed to read Java runtimes.");
        }
        return ToolResult.success(renderList(runtimes,
                "Java runtimes HMCL currently knows about (" + runtimes.size() + "):"));
    }

    // ------------------------------------------------------------------
    // operation: refresh
    // ------------------------------------------------------------------

    private ToolResult refresh() {
        // JavaManager.refresh() kicks off an asynchronous disk scan that replaces the runtime list and
        // publishes it via getAllJavaProperty() on the FX thread when done. Trigger it, then wait a
        // bounded time for that publish (fires quickly when the set actually changed); if nothing
        // changed the property does not fire — the wait simply elapses and we report the current list.
        CountDownLatch latch = new CountDownLatch(1);
        ChangeListener<Collection<JavaRuntime>> listener = (obs, oldValue, newValue) -> latch.countDown();

        boolean fxWaited;
        try {
            Platform.runLater(() -> {
                JavaManager.getAllJavaProperty().addListener(listener);
                JavaManager.refresh();
            });
            fxWaited = latch.await(REFRESH_WAIT_SECONDS, TimeUnit.SECONDS);
            Platform.runLater(() -> JavaManager.getAllJavaProperty().removeListener(listener));
        } catch (IllegalStateException fxNotStarted) {
            // No JavaFX runtime (e.g. a headless unit test): trigger a best-effort scan and report.
            try {
                JavaManager.refresh();
            } catch (Throwable ignored) {
                // best effort
            }
            List<JavaRuntime> now = safeGetAll();
            return ToolResult.success(renderList(now == null ? List.of() : now,
                    "Re-scan requested. Java runtimes currently known (" + (now == null ? 0 : now.size()) + "):"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Interrupted while re-scanning Java runtimes.");
        }

        List<JavaRuntime> now = safeGetAll();
        if (now == null) {
            return ToolResult.failure("Re-scan requested, but the Java runtime list could not be read.");
        }
        String header = fxWaited
                ? "Re-scanned this machine for Java runtimes (" + now.size() + "):"
                : "Re-scan triggered; the list was unchanged after " + REFRESH_WAIT_SECONDS
                        + "s (a longer scan may still be running). Java runtimes currently known (" + now.size() + "):";
        return ToolResult.success(renderList(now, header));
    }

    // ------------------------------------------------------------------
    // operation: add
    // ------------------------------------------------------------------

    private ToolResult add(Map<String, Object> parameters) {
        String pathStr = str(parameters.get("path"));
        if (pathStr == null) {
            return ToolResult.failure("Provide 'path': the absolute path to a java/java.exe executable, or to a "
                    + "JDK/JRE home directory.");
        }

        final Path binary;
        try {
            binary = resolveJavaBinary(Paths.get(pathStr));
        } catch (java.nio.file.InvalidPathException e) {
            return ToolResult.failure("'path' is not a valid filesystem path: " + pathStr);
        }
        if (binary == null) {
            return ToolFailures.failure(
                    "Could not find a Java executable at or under '" + pathStr + "'",
                    ToolFailures.Retryable.YES,
                    "the path is neither a java/java.exe executable nor a JDK/JRE home containing bin/java",
                    "pass the path to the 'java' executable itself, or to the JDK/JRE home directory that contains "
                            + "bin/" + javaExecutableName());
        }

        // Already registered?
        List<JavaRuntime> before = safeGetAll();
        if (before != null) {
            for (JavaRuntime java : before) {
                if (samePath(java.getBinary(), binary)) {
                    return ToolResult.success("That Java runtime is already registered:\n  "
                            + describe(java) + "\nNo change made.");
                }
            }
        }

        try {
            // Native path (JavaManagementPage "add"): getAddJavaTask probes the executable, records it in
            // user settings and inserts it into the runtime map (on the FX thread). Run it to completion
            // through the shared blocking helper so this synchronous tool waits for the result.
            ContentToolSupport.runTaskBlocking(JavaManager.getAddJavaTask(binary), ADD_TIMEOUT_SECONDS,
                    "Register Java runtime at " + binary);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Interrupted while registering the Java runtime.");
        } catch (UnsupportedPlatformException e) {
            return ToolFailures.failure(
                    "That Java runtime is not compatible with this machine's platform and cannot be added",
                    ToolFailures.Retryable.NO,
                    "the runtime's architecture/OS does not match this platform (" + e.getMessage() + ")",
                    "add a Java runtime built for this platform instead");
        } catch (Throwable e) {
            Throwable cause = rootCause(e);
            if (cause instanceof UnsupportedPlatformException) {
                return ToolFailures.failure(
                        "That Java runtime is not compatible with this machine's platform and cannot be added",
                        ToolFailures.Retryable.NO,
                        "the runtime's architecture/OS does not match this platform",
                        "add a Java runtime built for this platform instead");
            }
            return ToolResult.failure("Failed to register the Java runtime at '" + binary + "': "
                    + (cause.getMessage() != null ? cause.getMessage() : cause.toString())
                    + " — make sure it is a working java/java.exe executable.");
        }

        // Report the freshly registered runtime if we can find it.
        List<JavaRuntime> after = safeGetAll();
        if (after != null) {
            for (JavaRuntime java : after) {
                if (samePath(java.getBinary(), binary)) {
                    return ToolResult.success("Registered Java runtime with HMCL:\n  " + describe(java)
                            + "\nHMCL can now select it for instances.");
                }
            }
        }
        return ToolResult.success("Registered the Java runtime at '" + binary + "' with HMCL.");
    }

    // ------------------------------------------------------------------
    // operation: uninstall (managed runtimes only) — DANGEROUS
    // ------------------------------------------------------------------

    private ToolResult uninstall(Map<String, Object> parameters) {
        List<JavaRuntime> all = safeGetAll();
        if (all == null) {
            return ToolResult.failure("Failed to read Java runtimes.");
        }

        List<JavaRuntime> managed = new ArrayList<>();
        for (JavaRuntime java : all) {
            if (java.isManaged()) {
                managed.add(java);
            }
        }
        if (managed.isEmpty()) {
            return ToolFailures.failure(
                    "There are no HMCL-downloaded (managed) Java runtimes to uninstall",
                    ToolFailures.Retryable.NO,
                    "uninstall only removes runtimes HMCL itself downloaded; none are installed",
                    "use download_java to install a managed runtime first, or (for a system/user JDK) remove it "
                            + "outside HMCL — this tool will not delete a JDK HMCL did not install");
        }

        Integer major = parseMajor(parameters.get("javaVersion"));
        String pathMatch = str(parameters.get("path"));
        if (major == null && pathMatch == null) {
            return ToolFailures.failure(
                    "Specify which managed runtime to uninstall",
                    ToolFailures.Retryable.YES,
                    "no 'javaVersion' or 'path' was given to pick a target",
                    "pass javaVersion=<major> and/or path=<substring>. Managed runtimes:\n"
                            + renderManaged(managed));
        }

        String needle = pathMatch == null ? null : pathMatch.toLowerCase(Locale.ROOT);
        List<JavaRuntime> candidates = new ArrayList<>();
        for (JavaRuntime java : managed) {
            if (major != null && java.getParsedVersion() != major) {
                continue;
            }
            if (needle != null && !java.getBinary().toString().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            candidates.add(java);
        }

        if (candidates.isEmpty()) {
            return ToolFailures.failure(
                    "No HMCL-managed Java runtime matches "
                            + (major != null ? "major version " + major : "")
                            + (major != null && needle != null ? " and " : "")
                            + (needle != null ? "path substring '" + pathMatch + "'" : ""),
                    ToolFailures.Retryable.YES,
                    "the version/path did not match any managed runtime (a matching runtime may be a "
                            + "system/user JDK, which this tool will not delete)",
                    "managed runtimes:\n" + renderManaged(managed));
        }
        if (candidates.size() > 1) {
            return ToolFailures.failure(
                    "Ambiguous: " + candidates.size() + " managed runtimes match",
                    ToolFailures.Retryable.YES,
                    "more than one managed runtime matches the given version/path",
                    "narrow it down with a more specific 'path' substring. Matches:\n" + renderManaged(candidates));
        }

        JavaRuntime target = candidates.get(0);
        String description = describe(target);
        try {
            // Native path (JavaManagementPage uninstall): getUninstallJavaTask removes it from the map
            // (FX thread) and deletes the managed install directory from disk.
            ContentToolSupport.runTaskBlocking(JavaManager.getUninstallJavaTask(target), UNINSTALL_TIMEOUT_SECONDS,
                    "Uninstall managed Java " + target.getParsedVersion());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("The Java uninstall was cancelled.");
        } catch (Throwable e) {
            Throwable cause = rootCause(e);
            return ToolResult.failure("Failed to uninstall the managed Java runtime (" + description + "): "
                    + (cause.getMessage() != null ? cause.getMessage() : cause.toString())
                    + " — some files may be held open by a running game.");
        }

        // Confirm it is actually gone (getUninstallJavaTask no-ops if the runtime is not under the
        // managed root; surface that instead of falsely claiming a deletion).
        List<JavaRuntime> after = safeGetAll();
        if (after != null) {
            for (JavaRuntime java : after) {
                if (samePath(java.getBinary(), target.getBinary())) {
                    return ToolFailures.failure(
                            "The runtime (" + description + ") is still present after the uninstall attempt",
                            ToolFailures.Retryable.NO,
                            "it is not located under HMCL's managed Java directory, so it was not deleted",
                            "this runtime cannot be removed by HMCL; delete it outside the launcher if intended");
                }
            }
        }
        return ToolResult.success("Uninstalled the HMCL-managed Java runtime:\n  " + description
                + "\nIts install directory was deleted. HMCL will re-download a runtime automatically if a launch "
                + "needs that Java version again.");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /// Snapshot of the known runtimes (never the live UI-bound collection), or {@code null} on failure.
    @Nullable
    private static List<JavaRuntime> safeGetAll() {
        try {
            List<JavaRuntime> list = new ArrayList<>(JavaManager.getAllJava());
            list.sort(null); // JavaRuntime is Comparable (managed-first, then version/arch/path)
            return list;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String renderList(List<JavaRuntime> runtimes, String header) {
        if (runtimes.isEmpty()) {
            return "No Java runtimes are known yet. HMCL can download a suitable one automatically when launching a "
                    + "game, or use operation=add to register one you already have.";
        }
        StringBuilder sb = new StringBuilder(header).append('\n');
        for (JavaRuntime java : runtimes) {
            sb.append("  - ").append(describe(java)).append('\n');
        }
        return sb.toString().trim();
    }

    private static String renderManaged(List<JavaRuntime> managed) {
        StringBuilder sb = new StringBuilder();
        for (JavaRuntime java : managed) {
            sb.append("  - ").append(describe(java)).append('\n');
        }
        return sb.toString().trim();
    }

    private static String describe(JavaRuntime java) {
        StringBuilder sb = new StringBuilder();
        sb.append(java.getVersion()).append(" [").append(java.isJDK() ? "JDK" : "JRE").append("] ")
                .append(java.getArchitecture());
        String vendor = java.getVendor();
        if (vendor != null && !vendor.isEmpty()) {
            sb.append(" · ").append(vendor);
        }
        sb.append(java.isManaged() ? " · HMCL-managed" : " · external");
        sb.append("\n      path: ").append(java.getBinary());
        return sb.toString();
    }

    /// Resolves a user-supplied path to a Java executable: accepts the executable itself, a JDK/JRE
    /// home directory (looks under {@code bin/}), or a {@code bin} directory. Returns {@code null} when
    /// no executable is found.
    @Nullable
    private static Path resolveJavaBinary(Path input) {
        String exe = javaExecutableName();
        List<Path> candidates = new ArrayList<>();
        if (Files.isRegularFile(input)) {
            candidates.add(input);
        }
        candidates.add(input.resolve("bin").resolve(exe));
        candidates.add(input.resolve(exe));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String javaExecutableName() {
        return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? "java.exe" : "java";
    }

    private static boolean samePath(Path a, Path b) {
        try {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (Throwable e) {
            return a.equals(b);
        }
    }

    @Nullable
    private static Integer parseMajor(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        // Tolerate "Java 21" / "21" / "1.8" (→ 8).
        int major = InstanceToolSupport.parseInt(text.replaceAll("[^0-9].*$", ""), Integer.MIN_VALUE);
        if (major == Integer.MIN_VALUE) {
            return null;
        }
        return major == 1 ? 8 : major;
    }

    private static Throwable rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    @Nullable
    private static String str(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
