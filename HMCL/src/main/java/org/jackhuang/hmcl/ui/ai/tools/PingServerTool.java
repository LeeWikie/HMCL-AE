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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.ai.tools.Tool;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.util.ServerAddress;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/// A read-only tool that pings a Minecraft (Java Edition) server and reports its
/// status: online/offline, MOTD, player count, server version and round-trip latency.
///
/// HMCL has no built-in server-status client (its [`ServerAddress`] helper only
/// parses `host[:port]`), so this implements the standard Minecraft
/// [Server List Ping](https://minecraft.wiki/w/Java_Edition_protocol#Status) handshake
/// over a plain [`Socket`] with connect/read timeouts:
///   handshake (next-state = status) → status request → status response (JSON) →
///   ping → pong (for latency).
///
/// Notes / limitations: only Java Edition servers are supported, and DNS SRV records
/// are NOT resolved — pass the explicit port if the server uses a non-default one.
///
/// Permission level: READ_ONLY. It opens an outbound TCP connection but changes nothing.
@NotNullByDefault
public final class PingServerTool implements Tool {

    private static final int DEFAULT_PORT = 25565;
    private static final int TIMEOUT_MS = 5000;

    @Override
    public String getName() {
        return "ping_server";
    }

    @Override
    public String getDescription() {
        return "Pings a Minecraft Java Edition server and reports whether it is online, its MOTD, "
                + "the current/maximum player count, the server version and the round-trip latency. "
                + "Parameter: address (required) — 'host' or 'host:port' (defaults to port 25565). "
                + "Read-only: it opens an outbound TCP connection but never modifies anything. "
                + "Only Java Edition servers are supported and DNS SRV records are not resolved "
                + "(pass an explicit port for non-default ports).";
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        Object addressObj = parameters.get("address");
        if (!(addressObj instanceof String) || ((String) addressObj).trim().isEmpty()) {
            addressObj = parameters.get("query");
        }
        if (!(addressObj instanceof String) || ((String) addressObj).trim().isEmpty()) {
            return ToolResult.failure("Parameter 'address' (host or host:port) is required.");
        }
        String raw = ((String) addressObj).trim();

        String host;
        int port;
        try {
            ServerAddress parsed = ServerAddress.parse(raw);
            host = parsed.getHost();
            port = parsed.getPort() >= 0 ? parsed.getPort() : DEFAULT_PORT;
        } catch (Throwable e) {
            return ToolResult.failure("Invalid server address '" + raw + "': " + e.getMessage());
        }
        if (host.isEmpty()) {
            return ToolResult.failure("Invalid server address '" + raw + "': empty host.");
        }

        try (Socket socket = new Socket()) {
            long connectStart = System.nanoTime();
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // Handshake packet (id 0x00): protocol version, host, port, next state = 1 (status).
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream hs = new DataOutputStream(handshake);
            writeVarInt(hs, 0x00);
            writeVarInt(hs, -1); // protocol version: -1 = unspecified, accepted for status
            writeString(hs, host);
            hs.writeShort(port);
            writeVarInt(hs, 1);
            writePacket(out, handshake.toByteArray());

            // Status request packet (id 0x00, empty body).
            ByteArrayOutputStream statusRequest = new ByteArrayOutputStream();
            writeVarInt(new DataOutputStream(statusRequest), 0x00);
            writePacket(out, statusRequest.toByteArray());
            out.flush();

            // Status response.
            readVarInt(in); // total packet length (ignored)
            int responseId = readVarInt(in);
            if (responseId != 0x00) {
                return ToolResult.failure("Unexpected status response (packet id " + responseId
                        + ") from " + host + ":" + port + " — not a Minecraft Java server?");
            }
            int jsonLength = readVarInt(in);
            if (jsonLength <= 0 || jsonLength > 4 * 1024 * 1024) {
                return ToolResult.failure("Malformed status response from " + host + ":" + port + ".");
            }
            byte[] jsonBytes = new byte[jsonLength];
            in.readFully(jsonBytes);
            String json = new String(jsonBytes, StandardCharsets.UTF_8);

            // Ping/pong for latency measurement.
            long latencyMs = -1;
            try {
                ByteArrayOutputStream ping = new ByteArrayOutputStream();
                DataOutputStream pd = new DataOutputStream(ping);
                writeVarInt(pd, 0x01);
                pd.writeLong(System.nanoTime());
                writePacket(out, ping.toByteArray());
                out.flush();

                long pingStart = System.nanoTime();
                readVarInt(in); // length
                int pongId = readVarInt(in);
                if (pongId == 0x01) {
                    in.readLong();
                    latencyMs = (System.nanoTime() - pingStart) / 1_000_000L;
                }
            } catch (IOException ignored) {
                // Latency is best-effort; the status above already proves the server is online.
            }
            if (latencyMs < 0) {
                latencyMs = (System.nanoTime() - connectStart) / 1_000_000L;
            }

            return ToolResult.success(format(host, port, json, latencyMs));
        } catch (java.net.UnknownHostException e) {
            return ToolResult.failure("Cannot resolve host '" + host + "'.");
        } catch (java.net.SocketTimeoutException e) {
            return ToolResult.success("Server " + host + ":" + port + " is OFFLINE or unreachable (connection timed out).");
        } catch (java.net.ConnectException e) {
            return ToolResult.success("Server " + host + ":" + port + " is OFFLINE (connection refused).");
        } catch (Throwable e) {
            return ToolResult.failure("Failed to ping " + host + ":" + port + ": " + e.getMessage());
        }
    }

    private static String format(String host, int port, String json, long latencyMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Server ").append(host).append(':').append(port).append(" is ONLINE.\n");
        sb.append("Latency: ").append(latencyMs).append(" ms\n");
        try {
            JsonElement root = JsonParser.parseString(json);
            if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();

                String motd = extractText(obj.get("description"));
                if (!motd.isEmpty()) {
                    sb.append("MOTD: ").append(motd.replace('\n', ' ').trim()).append('\n');
                }

                if (obj.has("version") && obj.get("version").isJsonObject()) {
                    JsonObject version = obj.getAsJsonObject("version");
                    if (version.has("name")) {
                        sb.append("Version: ").append(version.get("name").getAsString()).append('\n');
                    }
                }

                if (obj.has("players") && obj.get("players").isJsonObject()) {
                    JsonObject players = obj.getAsJsonObject("players");
                    int online = players.has("online") ? players.get("online").getAsInt() : -1;
                    int max = players.has("max") ? players.get("max").getAsInt() : -1;
                    sb.append("Players: ").append(online < 0 ? "?" : online)
                            .append(" / ").append(max < 0 ? "?" : max).append('\n');
                }
            }
        } catch (Throwable e) {
            sb.append("(Could not fully parse the status JSON: ").append(e.getMessage()).append(")\n");
        }
        return sb.toString().trim();
    }

    /// Extracts plain text from a MOTD value, which may be a string or a chat
    /// component object with `text` and nested `extra` parts.
    private static String extractText(@org.jetbrains.annotations.Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        StringBuilder sb = new StringBuilder();
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("text")) {
                sb.append(obj.get("text").getAsString());
            }
            if (obj.has("extra") && obj.get("extra").isJsonArray()) {
                for (JsonElement child : obj.getAsJsonArray("extra")) {
                    sb.append(extractText(child));
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                sb.append(extractText(child));
            }
        }
        return sb.toString();
    }

    private static void writePacket(DataOutputStream out, byte[] data) throws IOException {
        writeVarInt(out, data.length);
        out.write(data);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            byte current = in.readByte();
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                break;
            }
            position += 7;
            if (position >= 32) {
                throw new IOException("VarInt is too big");
            }
        }
        return value;
    }
}
