---
name: use-modpacks
description: How to install, export and update modpacks — find a pack, install it as its own instance, export the user's setup as .mrpack, keep packs updated. Use when the user mentions modpacks ("整合包","装整合","modpack","mrpack").
version: 1.0
triggers: 整合包, 装整合, 玩整合, modpack, mrpack, curseforge包, 整合
---

# Modpacks

Goal: a working modpack instance with zero manual steps — and honest handling of the pack
formats HMCL supports (CurseForge zip, Modrinth .mrpack, MultiMC zip).

## Install
1. **Find**: `search_modpacks(<name>)` — real, current results; never quote packs from memory.
2. **Confirm**: `ask` which pack/version if ambiguous. Warn about heavy packs on weak
   hardware (runtime context shows RAM; big packs want 6–8 GB — see `java-and-memory`).
3. **Install**: `install_modpack(name/id)` — creates its OWN instance (never merge a pack
   into an existing instance). Runs in background: report the job id, `check_job` before
   declaring success.
4. **Local file**: the user may have downloaded a pack zip themselves — `install_modpack`
   with the file path works for supported formats.
5. **After install**: `instance_details` to confirm loader/version; memory to 4–6 GB+ via
   `set_instance_memory` if the pack is large.

## Export (share your setup)
- `export_modpack(instance)` — packs the instance as a Modrinth-format `.mrpack`
  (background job). Tell the user where the file landed and that friends import it with
  any Modrinth-compatible launcher (including HMCL-AE: install_modpack on the file).

## Update a pack
- Packs update as a WHOLE: install the new pack version as a fresh instance, then migrate
  the user's data — copy saves (see `manage-worlds-and-backups`), options.txt, and
  serverlist if wanted. Never update mods inside a pack one-by-one (`update_mod` is for
  hand-built instances; it breaks pack version coherence).
- Keep the old instance until the new one is confirmed working, then offer `delete_instance`.

## Don't
- Don't install a pack into an existing instance.
- Don't hand-update individual mods inside a modpack.
- Don't guess pack names/versions — search first.
