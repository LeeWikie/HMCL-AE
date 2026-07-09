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

import org.jackhuang.hmcl.ai.search.AiSearchConfig;
import org.jackhuang.hmcl.ai.search.WebSearchTool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Regression test for the shared search-config instance (blueprint A1 / bugfix P5): the tools
/// (WebSearchTool) hold a REFERENCE to the AiSearchConfig instance, so setter edits made through
/// the settings page must be visible to the tool immediately — the original bug was the settings
/// page loading its own second instance, whose edits the tool never saw. No network is touched:
/// every asserted path (disabled gate, unknown provider) fails before any client is built/called.
public final class SharedSearchConfigTest {

    @Test
    public void toolReadsSetterChangesThroughTheSharedInstance() {
        AiSearchConfig config = new AiSearchConfig();
        config.setEnabled(false);
        WebSearchTool tool = new WebSearchTool(config);

        // Disabled → the tool must refuse.
        ToolResult disabled = tool.execute(Map.of("query", "minecraft crash"));
        assertFalse(disabled.isSuccess());
        assertTrue(disabled.getError().contains("disabled"),
                "disabled gate must report the disabled state: " + disabled.getError());

        // Flip the SAME instance's setters — the tool must see the new values on the next call
        // without being reconstructed.
        config.setEnabled(true);
        config.setProvider("zz-not-a-real-provider");
        ToolResult unknownProvider = tool.execute(Map.of("query", "minecraft crash"));
        assertFalse(unknownProvider.isSuccess());
        assertTrue(unknownProvider.getError().contains("zz-not-a-real-provider"),
                "the tool must have read the UPDATED provider value from the shared instance: "
                        + unknownProvider.getError());

        // And a second edit is picked up too (proves it is a live reference, not a one-shot copy).
        config.setProvider("zz-another-provider");
        ToolResult second = tool.execute(Map.of("query", "minecraft crash"));
        assertFalse(second.isSuccess());
        assertTrue(second.getError().contains("zz-another-provider"),
                "subsequent setter edits must also be visible: " + second.getError());
    }

    @Test
    public void emptyQueryStillFailsFastWhenEnabled() {
        AiSearchConfig config = new AiSearchConfig();
        config.setEnabled(true);
        WebSearchTool tool = new WebSearchTool(config);

        ToolResult result = tool.execute(Map.of());
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("query"),
                "missing query must fail before any provider lookup: " + result.getError());
    }
}
