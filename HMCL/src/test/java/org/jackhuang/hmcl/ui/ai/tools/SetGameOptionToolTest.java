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

import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// End-to-end coverage of [SetGameOptionTool]'s mandatory backup-before-edit precondition
/// (options.txt is the canonical in-place text config the agent edits): rewriting the instance's
/// existing options.txt must first snapshot it to a sibling `options.txt.bak` holding the pre-edit
/// content, while the option change itself still lands. The snapshot target is resolved the same
/// way the tool resolves the file — `repository.getRunDirectory(instance)/options.txt` — so this
/// exercises the real domain path, not just the shared [org.jackhuang.hmcl.ai.tools.FileBackup]
/// primitive.
public final class SetGameOptionToolTest {

    @Test
    public void setOptionSnapshotsOptionsTxtBeforeEditing() throws Exception {
        try (ProfileFixture fx = new ProfileFixture()) {
            String instance = fx.createInstance("Inst");
            Path runDir = fx.repository().getRunDirectory(instance);
            Files.createDirectories(runDir);
            Path optionsFile = runDir.resolve("options.txt");
            String original = "fov:70\nrenderDistance:8\n";
            Files.writeString(optionsFile, original, StandardCharsets.UTF_8);

            SetGameOptionTool tool = new SetGameOptionTool();
            ToolResult result = tool.execute(Map.of("instance", instance, "key", "fov", "value", "90"));

            assertTrue(result.isSuccess(), "expected success: " + result.getError());
            assertTrue(Files.readString(optionsFile, StandardCharsets.UTF_8).contains("fov:90"),
                    "the option edit must land");

            Path bak = optionsFile.resolveSibling("options.txt.bak");
            assertTrue(Files.exists(bak), "a mandatory .bak snapshot of options.txt must exist after set_option");
            assertEquals(original, Files.readString(bak, StandardCharsets.UTF_8),
                    "the .bak must hold the PRE-edit options.txt content");
        }
    }
}
