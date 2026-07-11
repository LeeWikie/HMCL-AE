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
package org.jackhuang.hmcl.ai.tools;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Locks in the web_fetch SSRF guard: internal / loopback / link-local / RFC1918 / cloud-metadata
/// targets are refused, public addresses pass. Uses IP literals so no DNS lookup is needed.
///
/// Also locks in the "Class B" defense-in-depth documented on [`WebFetchTool`]: the guard must
/// keep running exactly the same way whether or not a proxy is configured (it cannot be gated
/// behind "a proxy will handle it"), while a proxy being configured must not cause legitimate
/// public targets to be over-blocked either.
public final class WebFetchToolTest {

    @Test
    void blocksInternalAndMetadataAddresses() {
        assertNotNull(WebFetchTool.blockedReason(URI.create("http://127.0.0.1/")), "loopback");
        assertNotNull(WebFetchTool.blockedReason(URI.create("http://[::1]/")), "ipv6 loopback");
        assertNotNull(WebFetchTool.blockedReason(URI.create("http://169.254.169.254/latest/meta-data/")), "cloud metadata");
        assertNotNull(WebFetchTool.blockedReason(URI.create("http://10.0.0.5/")), "rfc1918 10/8");
        assertNotNull(WebFetchTool.blockedReason(URI.create("http://192.168.1.1/")), "rfc1918 192.168/16");
        assertNotNull(WebFetchTool.blockedReason(URI.create("http://172.16.5.5/")), "rfc1918 172.16/12");
        assertNotNull(WebFetchTool.blockedReason(URI.create("http:///nohost")), "missing host");
    }

    @Test
    void allowsPublicIpLiteral() {
        assertNull(WebFetchTool.blockedReason(URI.create("http://1.1.1.1/")));
        assertNull(WebFetchTool.blockedReason(URI.create("https://8.8.8.8/")));
    }

    /// A proxy pointed at an address nothing listens on. Used to prove which code path ran
    /// without depending on real network access: if `execute()` ever tried to actually go
    /// through this proxy, the failure text would be a connectivity error ("Fetch failed: ..."),
    /// never the SSRF-guard message.
    private static ProxySelector unreachableProxySelector() {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 1)));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                // no-op
            }
        };
    }

    @Test
    void ssrfGuardStillEnforcedWhenProxyConfigured() {
        ProxySelector original = ProxySelector.getDefault();
        try {
            ProxySelector.setDefault(unreachableProxySelector());

            ToolResult result = new WebFetchTool().execute(Map.of("url", "http://127.0.0.1/"));

            assertFalse(result.isSuccess(), "loopback target must still be refused when a proxy is configured");
            assertNotNull(result.getError());
            assertTrue(result.getError().contains("SSRF guard"),
                    "expected the local-resolution SSRF guard to fire before any proxy connection "
                            + "attempt (i.e. the guard must not be skipped just because a proxy is "
                            + "configured), got: " + result.getError());
        } finally {
            ProxySelector.setDefault(original);
        }
    }

    @Test
    void proxyConfiguredDoesNotFalsePositiveBlockPublicTarget() {
        ProxySelector original = ProxySelector.getDefault();
        try {
            ProxySelector.setDefault(unreachableProxySelector());

            ToolResult result = new WebFetchTool().execute(Map.of("url", "http://1.1.1.1/"));

            // The public target must pass the SSRF guard even though a proxy is configured; the
            // eventual failure here is a legitimate connectivity error (this fake proxy refuses
            // the connection), proving blockedReason() let the target through instead of
            // over-blocking it merely because a proxy is present.
            assertFalse(result.isSuccess(), "the fake unreachable proxy should make the fetch fail");
            assertNotNull(result.getError());
            assertFalse(result.getError().contains("SSRF guard"),
                    "a public target must not be rejected by the SSRF guard just because a proxy "
                            + "is configured, got: " + result.getError());
        } finally {
            ProxySelector.setDefault(original);
        }
    }
}
