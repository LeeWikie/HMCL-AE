---
name: multiplayer-and-servers
description: How to help the user play with friends — join a server, check why a server is unreachable, set up LAN play, and match version/mods with the server. Use when the user mentions multiplayer, joining a server, LAN, or playing with friends ("联机","进服","服务器","局域网","一起玩").
version: 1.0
triggers: 联机, 服务器, 进服, 加服, 开服, 局域网, 直连, 一起玩, 和朋友玩, 进不去服, multiplayer, server, join server, lan
---

# Multiplayer & servers

Goal: get the user actually connected — diagnose reachability, match the client to the
server, and explain what they must ask the server owner when info is missing.

## Step 1 — Understand what they have
1. `list_instances` — which instance they intend to play on (version + loader matter for joining).
2. `list_servers(instance)` — servers already saved in their server list (name + address).
3. If they gave a new address, use it directly; otherwise work with the saved entries.

## Step 2 — Check reachability BEFORE debugging the client
- `ping_server(address)` — real Server List Ping: returns online/offline, version, player count, MOTD.
- Unreachable → likely wrong address/port, server offline, or firewall. Tell the user in
  plain language what to check with the server owner. Do NOT touch the client yet.
- Reachable → compare the reported server version with the instance version (Step 3).

## Step 3 — Match the client to the server
- **Version mismatch** is the #1 join failure: the server reports its version in `ping_server`.
  If the user's instance differs, offer to create a matching instance (`install_loader` with the
  right gameVersion) — do not downgrade/upgrade an existing instance in place.
- **Modded server** (Forge/Fabric): the client generally needs the same loader and the same
  major mods. Ask the user for the server's modpack/mod list (or its CurseForge link); install
  with `install_modpack` / `install_mod` into the matching instance.
- **"正版" (online-mode) server**: an offline account cannot join. Check `list_accounts` —
  if they only have an offline account, explain they need a Microsoft account for this server
  (`microsoft_login`), or ask the server owner whether offline mode is allowed.

## Step 4 — LAN play (same house / same Wi-Fi)
- Host: open a singleplayer world → Esc → 对局域网开放 (Open to LAN) → note the port shown.
- Guest: 多人游戏 → the LAN world appears automatically; if not, 直接连接 with `<host-ip>:<port>`.
  Get the host's IP via shell (`ipconfig` on Windows, `ip addr` on Linux) if needed.
- Offline accounts on LAN must have DIFFERENT usernames, or the second player is kicked.
- LAN does not work across different networks — for remote friends suggest a real server or
  a tunneling service, and be honest that HMCL-AE cannot set those up by itself.

## Don't
- Don't blame the client before `ping_server` has shown the server is actually reachable.
- Don't edit servers.dat by hand; the in-game 多人游戏 screen manages it.
- Don't promise cross-network LAN — it needs a public server or tunnel.
