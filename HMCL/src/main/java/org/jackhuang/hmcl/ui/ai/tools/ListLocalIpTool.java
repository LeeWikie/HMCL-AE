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

import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jetbrains.annotations.NotNullByDefault;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/// Lists this machine's LAN-reachable IPv4 addresses — the one genuine gap left once shell was
/// turned off by default: "开对局域网开放" multiplayer needs the host's local IP for the guest to
/// connect to, and nothing else covers it (previously the only way was `shell` running
/// `ipconfig`/`ip addr`).
///
/// Permission level: READ_ONLY. It only reads local network interface info, no network calls.
@NotNullByDefault
public final class ListLocalIpTool implements Tool {

    @Override
    public String getName() {
        return "list_local_ip";
    }

    @Override
    public String getDescription() {
        return "Lists this machine's LAN-reachable IPv4 addresses (one per active, non-loopback network "
                + "interface). Use this for LAN multiplayer ('对局域网开放') — the host shares one of these "
                + "addresses with guests on the same network. Takes no parameters. Read-only, no network calls.";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        List<String> lines = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String name = iface.getDisplayName();
                        lines.add("  - " + address.getHostAddress()
                                + (name != null && !name.isBlank() ? "  (" + name + ")" : ""));
                    }
                }
            }
        } catch (SocketException e) {
            return ToolResult.failure("Failed to enumerate network interfaces: " + e.getMessage());
        }

        if (lines.isEmpty()) {
            return ToolResult.success("No LAN-reachable IPv4 address found on any active network interface.");
        }
        Collections.sort(lines);
        return ToolResult.success("This machine's LAN-reachable IPv4 addresses:\n" + String.join("\n", lines));
    }
}
