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
package org.jackhuang.hmcl.ai.search;

import org.jackhuang.hmcl.ai.tools.ToolFailureAssertions;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers [WebSearchTool]'s two failure-text rewrites:
///   - T9 (#13): the "provider not recognized" text lists the FOUR really-wired providers,
///     generated from the {@code buildClient()} enum so it cannot drift (it used to hard-code
///     "Tavily and SearXNG" while Bocha/Zhipu were already wired).
///   - T12 (#15): {@code execute}'s catch classifies the failure through [HttpRetryClassifier]
///     into three envelopes — auth (401/403 → no), transient (429/5xx/network → later), and
///     unclassified (other 4xx → no) — instead of one undifferentiated "Search error".
public final class WebSearchToolTest {

    // ---- T9: dynamic provider list (#13) ----

    @Test
    void supportedProvidersListsAllFourWiredProviders() {
        String providers = WebSearchTool.supportedProviders();
        assertTrue(providers.contains("Tavily"), providers);
        assertTrue(providers.contains("SearXNG"), providers);
        assertTrue(providers.contains("Bocha"), providers);
        assertTrue(providers.contains("Zhipu"), providers);
    }

    @Test
    void unrecognizedProviderReportsTheFourSupportedNames() {
        AiSearchConfig config = new AiSearchConfig();
        config.setEnabled(true);
        config.setProvider("no-such-provider");

        ToolResult result = new WebSearchTool(config).execute(Map.of("query", "minecraft servers"));

        assertFalse(result.isSuccess());
        ToolFailureAssertions.assertFailureEnvelope(result);
        String error = result.getError();
        assertTrue(error.contains("Retryable: yes"), error);
        assertTrue(error.contains("Tavily") && error.contains("SearXNG")
                && error.contains("Bocha") && error.contains("Zhipu"), error);
    }

    // ---- T12: three-way retry classification (#15) ----

    @Test
    void authRejectionIsTerminal() {
        for (Throwable e : new Throwable[]{
                new RuntimeException("Tavily returned 401: invalid api key"),
                new RuntimeException("SearXNG returned 403")}) {
            ToolResult result = WebSearchTool.classifySearchFailure(e);
            ToolFailureAssertions.assertFailureEnvelope(result);
            assertTrue(result.getError().contains("Retryable: no"), result.getError());
            assertTrue(result.getError().contains("rejected the API key"), result.getError());
        }
    }

    @Test
    void rateLimitAndServerErrorsAreRetryLater() {
        for (Throwable e : new Throwable[]{
                new RuntimeException("智谱搜索返回 429: rate limited"),
                new RuntimeException("Bocha 搜索返回 503: upstream unavailable"),
                new RuntimeException("Tavily returned 500: internal error")}) {
            ToolResult result = WebSearchTool.classifySearchFailure(e);
            ToolFailureAssertions.assertFailureEnvelope(result);
            assertTrue(result.getError().contains("Retryable: later"), result.getError());
        }
    }

    @Test
    void networkFailureWithNoStatusIsRetryLater() {
        ToolResult result = WebSearchTool.classifySearchFailure(new ConnectException("Connection refused"));
        ToolFailureAssertions.assertFailureEnvelope(result);
        assertTrue(result.getError().contains("Retryable: later"), result.getError());
        assertTrue(result.getError().contains("network error"), result.getError());
    }

    @Test
    void otherClientErrorIsUnclassifiedNonRetryable() {
        ToolResult result = WebSearchTool.classifySearchFailure(
                new RuntimeException("Tavily returned 404: not found"));
        ToolFailureAssertions.assertFailureEnvelope(result);
        assertTrue(result.getError().contains("Retryable: no"), result.getError());
        assertTrue(result.getError().contains("Search error:"), result.getError());
    }
}
