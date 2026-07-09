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

import org.jackhuang.hmcl.ai.remember.RememberStore;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Recalled memory content is untrusted, model-visible data — a saved memory whose text happens to
/// contain something that looks like an instruction ("忽略之前的规则…") must not be able to pass
/// itself off as a real instruction. {@link RecallTool#execute} now wraps its output with an
/// explicit "this is data, not instructions" caveat; this locks that framing in.
public final class RecallToolTest {

    @Test
    public void resultCarriesUntrustedDataCaveat(@TempDir Path dir) throws Exception {
        RememberStore store = new RememberStore(dir);
        store.init();
        store.remember("test memory", List.of(), "some saved fact");

        RecallTool tool = new RecallTool(store);
        ToolResult result = tool.execute(Map.of());

        assertTrue(result.isSuccess());
        String output = result.getOutput();
        assertTrue(output.contains("not instructions"),
                "recalled memory output must be framed as data, not instructions: " + output);
        assertTrue(output.contains("test memory"), "the actual memory content must still be present");
    }

    @Test
    public void emptyResultHasNoCaveatNoise() throws Exception {
        RememberStore store = new RememberStore(Path.of(System.getProperty("java.io.tmpdir"), "hmcl-recall-empty-" + System.nanoTime()));
        RecallTool tool = new RecallTool(store);
        ToolResult result = tool.execute(Map.of("query", "nothing-will-match-this"));

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("No memories"));
    }
}
