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
package org.jackhuang.hmcl.ui.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Build-time gate for the stage-C i18n migration (BF P11): no NEW hardcoded Chinese UI copy may
/// creep back into `ui/ai` source, and the three I18N properties files must stay in lockstep on
/// their `ai.*` key sets.
///
/// Scope notes:
///  - Only string literals passed to the common UI-text entry points are flagged
///    (`setText`/`setTitle`/`setSubtitle`/`setPromptText`/`setTooltip`, `new Label`/`new JFXButton`/
///    `new Tooltip`) — Chinese in comments, log messages or model-facing tool strings is fine.
///  - A line ending in `// i18n-exempt` is whitelisted by design (none needed today).
public final class HardcodedChineseScanTest {

    private static final Pattern SETTER = Pattern.compile(
            "set(Text|Title|Subtitle|PromptText|Tooltip)\\s*\\(\\s*\"[^\"]*[\\u4e00-\\u9fa5]");
    private static final Pattern CTOR = Pattern.compile(
            "new\\s+(Label|JFXButton|Tooltip)\\s*\\(\\s*\"[^\"]*[\\u4e00-\\u9fa5]");

    /// Resolves a path that works whether the test runs from the repo root or the HMCL module dir.
    private static Path resolve(String relative) {
        Path direct = Path.of(relative);
        if (Files.exists(direct)) return direct;
        Path fromRoot = Path.of("HMCL").resolve(relative);
        if (Files.exists(fromRoot)) return fromRoot;
        throw new IllegalStateException("cannot locate " + relative + " from " + Path.of("").toAbsolutePath());
    }

    @Test
    public void noHardcodedChineseUiLiterals() throws IOException {
        Path root = resolve("src/main/java/org/jackhuang/hmcl/ui/ai");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String trimmed = line.strip();
                    // Whitelist: comment lines (incl. the file header) and explicit exemptions.
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
                    if (line.contains("i18n-exempt")) continue;
                    if (SETTER.matcher(line).find() || CTOR.matcher(line).find()) {
                        violations.add(root.relativize(file) + ":" + (i + 1) + "  " + trimmed);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "hardcoded Chinese UI literals found (use i18n(...) instead):\n" + String.join("\n", violations));
    }

    @Test
    public void aiKeySetsIdenticalAcrossAllThreeBundles() throws IOException {
        Path langDir = resolve("src/main/resources/assets/lang");
        Set<String> en = aiKeys(langDir.resolve("I18N.properties"));
        Set<String> zh = aiKeys(langDir.resolve("I18N_zh.properties"));
        Set<String> zhCn = aiKeys(langDir.resolve("I18N_zh_CN.properties"));
        assertEquals(zhCn, en, "ai.* keys of I18N.properties must match I18N_zh_CN.properties");
        assertEquals(zhCn, zh, "ai.* keys of I18N_zh.properties must match I18N_zh_CN.properties");
    }

    private static Set<String> aiKeys(Path propertiesFile) throws IOException {
        Set<String> keys = new TreeSet<>();
        for (String line : Files.readAllLines(propertiesFile, StandardCharsets.UTF_8)) {
            if (line.startsWith("ai.")) {
                int eq = line.indexOf('=');
                if (eq > 0) keys.add(line.substring(0, eq).trim());
            }
        }
        return keys;
    }
}
