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

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// Post-write validation for [`EditTool`] / [`WriteFileTool`] — the HMCL-AE analogue of
/// opencode's "run LSP diagnostics after every write". Routed by file extension:
///
///   - `.json` — re-parse the just-written text; a syntax error yields a WARNING carrying
///     Gson's line/column diagnostics.
///   - `.properties` — scan line by line; non-blank, non-comment lines without a `=`/`:`
///     separator (or starting with one, i.e. an empty key) are flagged with their line numbers.
///
/// The warning is appended to the **success** receipt — the file HAS been written, so failing
/// the call would lie about the on-disk state; the model just must not assume the change is
/// valid. NBT validation (`SetNbtTool`/`TransferInventoryTool`) is intentionally NOT handled
/// here; it lands in a later wave together with the world-lock rework.
@NotNullByDefault
public final class PostWriteValidator {

    private PostWriteValidator() {
    }

    /// Maximum number of malformed .properties lines quoted in one warning.
    private static final int MAX_REPORTED_LINES = 5;

    /// Validates `content` as just written to `file`. Returns a `"WARNING: ..."` text to
    /// append to the success receipt, or `null` when the format is fine / the extension has
    /// no validator.
    @Nullable
    public static String validate(Path file, String content) {
        Path name = file.getFileName();
        String fileName = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".json")) {
            return validateJson(content);
        }
        if (fileName.endsWith(".properties")) {
            return validateProperties(content);
        }
        return null;
    }

    @Nullable
    private static String validateJson(String content) {
        try {
            // JsonParser.parseString parses a single top-level element and rejects trailing
            // garbage — good enough to catch structurally broken writes.
            JsonParser.parseString(content);
            return null;
        } catch (JsonParseException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return "WARNING: the file was written but is no longer valid JSON — " + detail
                    + ". Review before assuming this took effect.";
        }
    }

    @Nullable
    private static String validateProperties(String content) {
        List<String> lines = content.lines().toList();
        List<String> bad = new ArrayList<>();
        boolean continuation = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean wasContinuation = continuation;
            String stripped = line.strip();
            continuation = endsWithOddBackslashes(stripped);
            if (wasContinuation) {
                continue; // logical continuation of the previous property — anything goes
            }
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("!")) {
                continue;
            }
            int eq = indexOfSeparator(stripped);
            if (eq < 0 || eq == 0) { // no key=value separator, or an empty key
                if (bad.size() < MAX_REPORTED_LINES) {
                    bad.add("line " + (i + 1) + ": '" + truncate(stripped) + "'");
                }
            }
        }
        if (bad.isEmpty()) {
            return null;
        }
        return "WARNING: the file was written but " + bad.size()
                + (bad.size() == 1 ? " line does" : " lines do")
                + " not look like 'key=value' pairs (" + String.join("; ", bad)
                + "). Review before assuming this took effect.";
    }

    /// First unescaped `=` or `:` separator, mirroring `java.util.Properties` closely enough
    /// for a lint (backslash escapes honoured; whitespace-separated keys are NOT accepted
    /// because the files the agent writes — server.properties and friends — never use them).
    private static int indexOfSeparator(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++; // skip the escaped character
                continue;
            }
            if (c == '=' || c == ':') {
                return i;
            }
        }
        return -1;
    }

    /// Whether the line ends with an odd number of backslashes — the `java.util.Properties`
    /// line-continuation marker.
    private static boolean endsWithOddBackslashes(String line) {
        int count = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            count++;
        }
        return (count & 1) == 1;
    }

    private static String truncate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }
}
