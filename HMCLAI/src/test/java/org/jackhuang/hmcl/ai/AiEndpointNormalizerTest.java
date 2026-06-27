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
package org.jackhuang.hmcl.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for {@link AiEndpointNormalizer}.
public final class AiEndpointNormalizerTest {

    /// Null or blank input yields null.
    @Test
    public void testNullInputReturnsNull() {
        assertNull(AiEndpointNormalizer.normalize(null, AiProtocolFamily.OPENAI_COMPLETIONS.getId()));
        assertNull(AiEndpointNormalizer.normalize("", AiProtocolFamily.OPENAI_COMPLETIONS.getId()));
        assertNull(AiEndpointNormalizer.normalize("   ", AiProtocolFamily.OPENAI_COMPLETIONS.getId()));
    }

    /// Full URL with scheme is preserved as-is.
    @Test
    public void testFullUrlPreserved() {
        String input = "https://api.openai.com/v1/chat/completions";
        String result = AiEndpointNormalizer.normalize(input,
                AiProtocolFamily.OPENAI_COMPLETIONS.getId());
        assertEquals(input, result);
    }

    /// Missing scheme defaults to https:// for remote hosts.
    @Test
    public void testMissingSchemeDefaultsToHttps() {
        String result = AiEndpointNormalizer.normalize("api.openai.com",
                AiProtocolFamily.OPENAI_COMPLETIONS.getId());
        assertTrue(result.startsWith("https://"), "Remote host should default to https");
        assertTrue(result.contains("api.openai.com"));
    }

    /// Localhost defaults to http://.
    @Test
    public void testLocalhostDefaultsToHttp() {
        String result = AiEndpointNormalizer.normalize("localhost:11434",
                AiProtocolFamily.OPENAI_COMPLETIONS.getId());
        assertTrue(result.startsWith("http://"), "Localhost should default to http");
        assertTrue(result.contains("localhost:11434"));
    }

    /// 127.0.0.1 defaults to http://.
    @Test
    public void testLoopbackDefaultsToHttp() {
        String result = AiEndpointNormalizer.normalize("127.0.0.1:8080",
                AiProtocolFamily.OPENAI_COMPLETIONS.getId());
        assertTrue(result.startsWith("http://"), "Loopback should default to http");
    }

    /// 192.168.x.x defaults to http://.
    @Test
    public void testPrivateIpDefaultsToHttp() {
        String result = AiEndpointNormalizer.normalize("192.168.1.100:8080",
                AiProtocolFamily.OPENAI_COMPLETIONS.getId());
        assertTrue(result.startsWith("http://"), "Private IP should default to http");
    }

    /// Bare host with OpenAI family appends /v1/chat/completions.
    @Test
    public void testOpenAiPathSuffixAppended() {
        String result = AiEndpointNormalizer.normalize("api.openai.com",
                AiProtocolFamily.OPENAI_COMPLETIONS.getId());
        assertTrue(result.endsWith("/v1/chat/completions"),
                "OpenAI family should append /v1/chat/completions");
    }

    /// Bare host with Anthropic family appends /v1/messages.
    @Test
    public void testAnthropicPathSuffixAppended() {
        String result = AiEndpointNormalizer.normalize("api.anthropic.com",
                AiProtocolFamily.ANTHROPIC.getId());
        assertTrue(result.endsWith("/v1/messages"),
                "Anthropic family should append /v1/messages");
    }

    /// REST API family leaves the endpoint as-is.
    @Test
    public void testRestApiNoSuffix() {
        String result = AiEndpointNormalizer.normalize("http://myserver/api/chat",
                AiProtocolFamily.RESTAPI.getId());
        assertEquals("http://myserver/api/chat", result);
    }

    /// Trailing slash is cleaned up before suffix appending.
    @Test
    public void testTrailingSlashBeforeSuffix() {
        String result = AiEndpointNormalizer.normalize("https://api.openai.com/",
                AiProtocolFamily.OPENAI_COMPLETIONS.getId());
        assertEquals("https://api.openai.com/v1/chat/completions", result);
    }

    /// Trailing slash on path with suffix already present is preserved.
    @Test
    public void testUserProvidedFullPathPreserved() {
        String result = AiEndpointNormalizer.normalize(
                "https://api.openai.com/v1/chat/completions/",
                AiProtocolFamily.OPENAI_COMPLETIONS.getId());
        assertEquals("https://api.openai.com/v1/chat/completions", result);
    }

    /// User-supplied path (not empty, not just /) is preserved.
    @Test
    public void testUserPathPreserved() {
        String result = AiEndpointNormalizer.normalize("http://localhost:11434/v1",
                AiProtocolFamily.OPENAI_COMPLETIONS.getId());
        assertTrue(result.startsWith("http://localhost:11434/v1"));
    }

    /// OpenAI reasoning family appends same suffix as completions.
    @Test
    public void testOpenAiReasoningSameSuffix() {
        String result = AiEndpointNormalizer.normalize("api.openai.com",
                AiProtocolFamily.OPENAI_REASONING.getId());
        assertTrue(result.endsWith("/v1/chat/completions"));
    }

    /// 10.x private range defaults to http.
    @Test
    public void testPrivate10RangeHttp() {
        String result = AiEndpointNormalizer.normalize("10.0.0.1:8080",
                AiProtocolFamily.OPENAI_COMPLETIONS.getId());
        assertTrue(result.startsWith("http://"));
    }

    /// 172.16.x private range defaults to http.
    @Test
    public void testPrivate172RangeHttp() {
        String result = AiEndpointNormalizer.normalize("172.16.0.1:8080",
                AiProtocolFamily.OPENAI_COMPLETIONS.getId());
        assertTrue(result.startsWith("http://"));
    }

    /// Unknown protocol family returns null.
    @Test
    public void testUnknownFamilyReturnsNull() {
        String result = AiEndpointNormalizer.normalize("api.example.com", "unknown-family");
        assertEquals("https://api.example.com", result);
    }
}
