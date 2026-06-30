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

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Locks in the web_fetch SSRF guard: internal / loopback / link-local / RFC1918 / cloud-metadata
/// targets are refused, public addresses pass. Uses IP literals so no DNS lookup is needed.
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
}
