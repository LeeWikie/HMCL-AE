---
name: manage-worlds-and-backups
description: How to manage singleplayer worlds — back up, restore, import/export maps, and recover from mistakes. Use when the user mentions saves, backups, restoring, or importing a downloaded map ("存档","备份","恢复","回档","导入地图").
version: 1.0
triggers: 存档, 备份, 回档, 恢复存档, 导入地图, 导入存档, 导出存档, 地图没了, 存档丢, world backup, restore world
---

# Worlds & backups

Goal: keep the user's worlds safe. Back up BEFORE anything risky, restore cleanly, and
never let a mistake become permanent.

## Tools
- `list_worlds(instance)` / `search_worlds` — what worlds exist (name, folder, version).
- `create_world_backup(world[, instance])` — timestamped snapshot (versioned full copy,
  auto-prunes beyond the retention setting). PREFER this over the older `backup_world`.
- `list_world_backups(world[, instance])` — snapshots, newest first, with ids.
- `restore_world_backup(world, backupId[, instance])` — roll back (DESTRUCTIVE, red-confirm;
  it auto-backs-up the current state first).
- `import_world(zip[, instance])` — import a downloaded map zip. `delete_world` — confirm-gated.
- `read_world_info(world)` — world version/seed/gamemode without opening the game.

## Common jobs
1. **"帮我备份存档"** — `list_worlds`, confirm which one (or all), `create_world_backup` each.
   Report where the snapshots live and the retention count.
2. **"恢复/回档"** — `list_world_backups` for the world, show the timestamps, let the user
   pick via `ask` (single-choice of the real backup ids), then `restore_world_backup`.
   NEVER restore while the game is running that world — ask the user to quit the world first.
3. **"导入下载的地图"** — get the zip path (or read_clipboard for a URL → download is NOT
   your job unless a tool covers it), `import_world(zip, instance)`. Zip-slip is guarded by
   the tool; if it refuses, the zip layout is wrong — check it contains a world folder with
   `level.dat` at the top level or one directory down.
4. **"存档不见了/坏了"** — check `list_worlds` for the expected instance (version isolation
   means each instance may have its OWN saves dir!); check `list_world_backups`; check the
   recycle bin for recently deleted folders. The most common cause is looking in the wrong
   instance, not data loss.

## Rules
- Before restore/delete/NBT edits: `create_world_backup` first. Always.
- Version isolation: saves may live under `versions/<instance>/saves` — trust `list_worlds`
  over guessing paths.
- Moving worlds to another computer = copy the world folder (or a backup zip); tell the user
  where it is (`open_game_folder` helps them see it).
