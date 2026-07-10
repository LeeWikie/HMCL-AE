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

import org.jackhuang.hmcl.ai.tools.ToolFailures;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// T7 (#12): the classic "search_worlds ping-pong" — CurseForge unconfigured tells the model to
/// use Modrinth, Modrinth has no Worlds category and tells it to switch source, forever. These
/// tests pin that [SearchWorldsTool] welds the terminal state instead: neither entry point (the
/// default CurseForge source with no key, nor an explicit Modrinth source) ever sends the model
/// back to Modrinth. The CurseForge API key is absent in the bare test JVM, so both paths resolve
/// to the terminal branch deterministically (same assumption as {@code InstallShaderToolTest} /
/// {@code DefaultAddonSourceTest}).
public final class SearchWorldsToolTest {

    private final SearchWorldsTool tool = new SearchWorldsTool();

    @Test
    void defaultSourceWeldsToTerminalWithoutBouncingToModrinth() {
        ToolResult result = tool.execute(Map.of("query", "medieval castle"));
        assertTerminalWorldSearch(result);
    }

    @Test
    void explicitModrinthSourceDoesNotBounceBack() {
        // The other half of the ping-pong: the model already switched to Modrinth. It must get the
        // welded terminal state, NOT the base class's "try a different source" (which points back
        // to CurseForge → "use Modrinth" → ...).
        ToolResult result = tool.execute(Map.of("query", "skyblock", "source", "modrinth"));
        assertTerminalWorldSearch(result);
    }

    @Test
    void curseForgeNotConfiguredMessageNeverPointsAtModrinth() {
        assertTerminalWorldSearch(tool.curseForgeNotConfigured());
        assertTerminalWorldSearch(tool.unavailableSource(ContentToolSupport.Source.MODRINTH));
    }

    @Test
    void baseClassStillPointsAtModrinthForOtherContentTypes() {
        // Proves the message was made overridable rather than globally rewritten: a content type
        // that Modrinth DOES serve still (correctly) suggests Modrinth as the keyless fallback.
        ToolResult result = new SearchResourcePacksTool().curseForgeNotConfigured();
        assertFalse(result.isSuccess());
        String error = result.getError();
        assertTrue(ToolFailures.isWellFormedEnvelope(error), () -> "not a well-formed envelope: " + error);
        assertTrue(error.contains("Retryable: yes"), error);
        assertTrue(error.toLowerCase().contains("modrinth"), error);
    }

    private static void assertTerminalWorldSearch(ToolResult result) {
        assertFalse(result.isSuccess());
        String error = result.getError();
        assertTrue(ToolFailures.isWellFormedEnvelope(error), () -> "not a well-formed envelope: " + error);
        assertTrue(error.contains("Retryable: no"), error);
        assertTrue(error.contains("Do not retry with source=\"modrinth\""), error);
        assertFalse(error.contains("Use the Modrinth source instead"),
                () -> "must not bounce the model back to Modrinth: " + error);
    }
}
