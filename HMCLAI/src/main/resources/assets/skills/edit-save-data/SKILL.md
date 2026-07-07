---
name: edit-save-data
description: How to edit world/player data safely with the NBT tools — inventory, position, gamemode, moving a player's data between worlds, offline UUIDs. Use when the user wants to change save data ("改存档","背包","传送","坐标","游戏模式","玩家数据").
version: 1.0
triggers: 改存档, 背包, 物品栏, 传送, 改坐标, 游戏模式, 改模式, 玩家数据, 转移背包, 搬背包, nbt, 离线uuid, 改数据
---

# Edit save data (NBT) — carefully

Goal: make precise, reversible edits to world/player NBT data. These are CATASTROPHIC-class
operations: a bad write corrupts the world. The tools enforce backups; you enforce precision.

## Tools
- `read_world_info(world)` — quick overview (version, seed, gamemode, spawn).
- `read_nbt(target)` / `get_nbt(target, path)` — inspect before you change anything.
- `set_nbt(target, path, value)` — the actual edit (red-confirm; auto-backup honoured).
- `delete_nbt(target, path)` — remove a tag (same safety class as set_nbt).
- `compute_offline_uuid(username)` — the UUID an offline player gets (`OfflinePlayer:<name>`).
- `copy_player_data(from, to)` / `transfer_inventory(from, to)` — move a player's data or
  inventory between players/worlds.
- `set_world_info` — world-level fields (name, gamemode, etc.) with the same guards.

## Procedure (always in this order)
1. **Locate**: `list_worlds` → confirm the world; the game must NOT be running it (tools
   detect the session lock and refuse — don't fight that, ask the user to quit the world).
2. **Backup**: `create_world_backup(world)` even though auto-backup may also run.
3. **Read first**: `get_nbt` the exact path you intend to change; show the user the current
   value and the new value; for anything non-obvious confirm via `ask`.
4. **Edit**: one `set_nbt` per logical change. Keep values exactly typed (ints stay ints —
   e.g. gamemode is an int: 0 survival / 1 creative / 2 adventure / 3 spectator).
5. **Verify**: `get_nbt` again and report the confirmed new value.

## Frequent requests
- **传送/改坐标**: player `Pos` is a list of 3 doubles in `level.dat` (singleplayer host) or
  `playerdata/<uuid>.dat`. Set all three values; keep Y sensible (not inside the ground).
- **改游戏模式**: `playerGameType` (int) in level.dat, or per-player `playerGameType`.
- **背包转移**: `transfer_inventory(from, to)` — from/to are player identifiers; for offline
  players derive the UUID via `compute_offline_uuid` when needed.
- **换电脑/换用户名后数据"丢了"**: usually the username→UUID changed. `compute_offline_uuid`
  for old and new names, then `copy_player_data` old→new.

## Don't
- Don't edit NBT by shell/hex — only the NBT tools (they keep compression + atomic writes).
- Don't batch many risky edits into one confirmation; do them one at a time.
- Don't touch a world the game has open.
