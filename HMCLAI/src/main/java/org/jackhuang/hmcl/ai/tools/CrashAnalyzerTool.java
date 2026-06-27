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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// An AI tool that parses Minecraft crash reports and produces structured analysis
/// with a confidence score to guide escalation decisions.
///
/// ## Confidence levels
///
/// - **HIGH**: A known pattern was matched (specific exception with mod name in stack).
/// - **MEDIUM**: Exception type identified but cause attribution is uncertain.
/// - **LOW**: Unable to parse the crash or extract meaningful diagnosis.
///
/// Low-confidence results should trigger Layer 2 escalation — automated modular
/// testing via {@link ModToggleTool} and {@link FileBackupTool}.
@NotNullByDefault
public final class CrashAnalyzerTool implements Tool {

    /// The confidence level of the crash analysis.
    public enum Confidence {
        HIGH, MEDIUM, LOW
    }

    // Matches the exception header line, e.g.:
    //   java.lang.NullPointerException: Cannot invoke "Object.toString()" because "obj" is null
    //   net.minecraftforge.fml.ModLoadingException: ExampleMod has failed to load correctly
    private static final Pattern EXCEPTION_HEADER =
            Pattern.compile("^([\\w.$]+(?:Exception|Error|Throwable))(?::\\s*(.*))?", Pattern.MULTILINE);

    // Matches a stack frame line, e.g.:
    //   at com.example.Mod.doSomething(Mod.java:42)
    //   at net.minecraft.client.Minecraft.lambda$run$0(Minecraft.java:100)
    private static final Pattern STACK_FRAME =
            Pattern.compile("^\\s+at\\s+([\\w.$]+)\\(([^)]+)\\)", Pattern.MULTILINE);

    // Matches the start of the mod list section in Forge/Fabric crash reports.
    // Typical headers:
    //   -- Mod List --
    //   Mod File: /.../mods/example.jar
    //   \tMod Name: Example Mod
    private static final Pattern MOD_LIST_START =
            Pattern.compile("^--\\s*Mod\\s*List\\s*--", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    // Matches individual mod entries of the form "    modid|Mod Name|version|..." (Forge)
    // or "    - modid (Mod Name) version" (Fabric)
    private static final Pattern MOD_ENTRY =
            Pattern.compile("^\\s*[*\\-|]?\\s*([\\w.-]+)\\s*(?:\\(([^)]+)\\)|\\|([^|]+)\\|)?",
                    Pattern.MULTILINE);

    /// Maximum number of stack frames to include in the analysis summary.
    private static final int MAX_STACK_FRAMES = 3;

    /// Maximum number of mod entries to report.
    private static final int MAX_MOD_ENTRIES = 30;

    /// Returns the unique name `"analyze_crash"`.
    @Override
    public String getName() {
        return "analyze_crash";
    }

    /// Returns a description explaining that this tool parses Minecraft crash reports.
    @Override
    public String getDescription() {
        return "Analyzes a Minecraft crash report text to extract the error type, "
                + "stack trace summary, mod list, and suspected cause. "
                + "Provide the full crash report as the 'crash_text' parameter.";
    }

    /// Parses the crash report text provided in the `"crash_text"` parameter.
    ///
    /// The returned [`ToolResult`] contains a structured summary of the crash,
    /// including the exception type, message, top stack frames, and loaded mods
    /// when available.
    ///
    /// @param parameters a map that must contain `"crash_text"` with a non-blank value
    /// @return a successful result with the analysis text, or a failure result
    ///         describing what went wrong
    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object raw = parameters.get("crash_text");
        if (raw == null || !(raw instanceof String)) {
            return ToolResult.failure("Missing or invalid 'crash_text' parameter: "
                    + "a non-empty crash report string is required.");
        }

        String crashText = ((String) raw).trim();
        if (crashText.isEmpty()) {
            return ToolResult.failure("Cannot analyze empty crash text.");
        }

        StringBuilder analysis = new StringBuilder();
        analysis.append("=== Minecraft Crash Analysis ===\n");

        // --- Extract exception header ---
        String exceptionClass = extractExceptionClass(crashText);
        String exceptionMessage = extractExceptionMessage(crashText);

        if (exceptionClass != null) {
            analysis.append("Error Type: ").append(exceptionClass).append("\n");
        } else {
            analysis.append("Error Type: (unknown — no exception header found)\n");
        }

        if (exceptionMessage != null) {
            analysis.append("Error Message: ").append(exceptionMessage).append("\n");
        }

        // --- Extract stack frames ---
        List<StackFrame> frames = extractStackFrames(crashText, MAX_STACK_FRAMES);
        if (!frames.isEmpty()) {
            analysis.append("Stack Trace (top ").append(frames.size()).append("):\n");
            for (int i = 0; i < frames.size(); i++) {
                StackFrame frame = frames.get(i);
                analysis.append("  ").append(i + 1).append(". ")
                        .append(frame.method).append("(").append(frame.location).append(")\n");
            }
        } else {
            analysis.append("Stack Trace: (none found)\n");
        }

        // --- Extract mod list ---
        List<String> mods = extractModList(crashText);
        if (!mods.isEmpty()) {
            analysis.append("Loaded Mods (").append(mods.size());
            if (mods.size() == MAX_MOD_ENTRIES) {
                analysis.append("+, truncated");
            }
            analysis.append("):\n");
            for (String mod : mods) {
                analysis.append("  - ").append(mod).append("\n");
            }
        } else {
            analysis.append("Loaded Mods: (none listed in report)\n");
        }

        // --- Add suspected cause ---
        String suspectedCause = suggestCause(exceptionClass, crashText);
        analysis.append("\nSuspected Cause: ").append(suspectedCause).append("\n");

        // --- Confidence assessment ---
        Confidence confidence = computeConfidence(exceptionClass, mods, analysis.toString());
        analysis.append("\nDiagnosis Confidence: ").append(confidence).append("\n");
        if (confidence == Confidence.LOW) {
            analysis.append("Recommendation: Escalate to automated modular testing.\n");
        }

        return ToolResult.success(analysis.toString());
    }

    /// Extracts the fully qualified exception class name from the crash report.
    ///
    /// @param crashText the raw crash report text
    /// @return the exception class name, or `null` if not found
    @Nullable
    private static String extractExceptionClass(String crashText) {
        Matcher m = EXCEPTION_HEADER.matcher(crashText);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /// Extracts the exception message (the text after the colon in the exception header).
    ///
    /// @param crashText the raw crash report text
    /// @return the exception message, or `null` if not present
    @Nullable
    private static String extractExceptionMessage(String crashText) {
        Matcher m = EXCEPTION_HEADER.matcher(crashText);
        if (m.find()) {
            String msg = m.group(2);
            if (msg != null && !msg.isBlank()) {
                return msg.trim();
            }
        }
        return null;
    }

    /// Extracts up to `limit` stack frames from the crash report.
    ///
    /// @param crashText the raw crash report text
    /// @param limit     maximum number of frames to return
    /// @return the extracted stack frames, never `null`
    private static List<StackFrame> extractStackFrames(String crashText, int limit) {
        List<StackFrame> frames = new ArrayList<>();
        Matcher m = STACK_FRAME.matcher(crashText);
        while (m.find() && frames.size() < limit) {
            frames.add(new StackFrame(m.group(1), m.group(2)));
        }
        return frames;
    }

    /// Extracts mod names from the `-- Mod List --` section of the crash report.
    ///
    /// Handles both Forge-style tabular entries and Fabric-style list entries.
    ///
    /// @param crashText the raw crash report text
    /// @return the list of mod names/ids, never `null`
    private static List<String> extractModList(String crashText) {
        List<String> mods = new ArrayList<>();

        Matcher sectionStart = MOD_LIST_START.matcher(crashText);
        if (!sectionStart.find()) {
            return mods;
        }

        // Search for mod entries in the region after the section header.
        int searchStart = sectionStart.end();
        String section = crashText.substring(searchStart);

        // Stop scanning when we hit the next `--` section header.
        Pattern sectionEnd = Pattern.compile("^--", Pattern.MULTILINE);
        Matcher endMatcher = sectionEnd.matcher(section);
        int searchEnd = section.length();
        if (endMatcher.find()) {
            searchEnd = endMatcher.start();
        }

        String modSection = section.substring(0, searchEnd);

        Matcher entryMatcher = MOD_ENTRY.matcher(modSection);
        while (entryMatcher.find() && mods.size() < MAX_MOD_ENTRIES) {
            String line = entryMatcher.group().trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            // Extract a readable name: prefer the parenthesized or pipe-delimited name,
            // otherwise use the modid.
            String name = entryMatcher.group(2);
            if (name == null) {
                name = entryMatcher.group(3);
            }
            if (name == null) {
                name = entryMatcher.group(1);
            }
            if (name != null && !name.isBlank()) {
                mods.add(name.trim());
            }
        }

        return mods;
    }

    /// Produces a simple suspected cause string based on the exception class
    /// and keywords in the crash text.
    ///
    /// This is a heuristic for the LLM to use as context; it is not exhaustive.
    ///
    /// @param exceptionClass the exception class name, may be `null`
    /// @param crashText       the raw crash report text
    /// @return a human-readable suspected cause description
    private static String suggestCause(@Nullable String exceptionClass, String crashText) {
        if (exceptionClass == null) {
            return "Unable to determine — no recognized exception in report.";
        }

        if (exceptionClass.contains("OutOfMemoryError")) {
            return "The game ran out of memory. Try allocating more RAM "
                    + "or reducing the number of mods/resource packs.";
        }
        if (exceptionClass.contains("ClassNotFoundException")
                || exceptionClass.contains("NoClassDefFoundError")) {
            return "A required class is missing. This often indicates a broken "
                    + "or incomplete mod installation.";
        }
        if (exceptionClass.contains("NoSuchMethodError")
                || exceptionClass.contains("NoSuchFieldError")
                || exceptionClass.contains("IncompatibleClassChangeError")) {
            return "A mod is incompatible with the current Minecraft or Forge/Fabric version. "
                    + "Try updating the mod or checking version compatibility.";
        }
        if (exceptionClass.contains("ModLoadingException")
                || crashText.contains("has failed to load correctly")) {
            return "A mod failed to load. Check the mod list above for the "
                    + "failing mod and verify it is compatible with your setup.";
        }
        if (exceptionClass.contains("NullPointerException")) {
            return "A null value was dereferenced. This is typically a bug in a mod "
                    + "or in the interaction between mods. Check the top stack frame "
                    + "to identify the responsible mod.";
        }
        if (exceptionClass.contains("RuntimeException")
                || exceptionClass.contains("Exception")) {
            return "A generic exception occurred. Review the stack trace above "
                    + "and the mod list to narrow down the cause.";
        }

        return "Unknown error type. Review the full crash report for more details.";
    }

    /// Computes a confidence level based on how much the analyzer could extract.
    private static Confidence computeConfidence(@Nullable String exceptionClass,
                                                List<String> mods, String analysis) {
        if (exceptionClass == null) return Confidence.LOW;
        if (analysis.contains("Suspected Cause: Unable to determine")
                || analysis.contains("Unknown error type")) {
            return Confidence.LOW;
        }
        if (!mods.isEmpty()
                && (analysis.contains("ModLoadingException")
                        || analysis.contains("has failed to load correctly")
                        || analysis.contains("IncompatibleClassChangeError")
                        || analysis.contains("NoSuchMethodError"))) {
            return Confidence.HIGH;
        }
        if (exceptionClass.contains("OutOfMemoryError")
                || analysis.contains("NullPointerException")) {
            return Confidence.HIGH;
        }
        return Confidence.MEDIUM;
    }

    /// A simple record holding a single stack frame's method name and source location.
    private record StackFrame(String method, String location) {
    }
}
