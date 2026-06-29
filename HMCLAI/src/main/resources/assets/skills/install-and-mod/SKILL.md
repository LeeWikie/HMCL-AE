---
name: install-and-mod
description: How to install a Minecraft version with a mod loader and add mods correctly, respecting version isolation. Use when the user wants to install/create a new version, set up Forge/Fabric/NeoForge/Quilt, or add mods ("装个版本","整合","装模组","加mod","Fabric","Forge","光影","资源包").
version: 1.0
---

# Install a version + add mods (the right way)

Do the whole thing with tools end-to-end. Never hand the user manual download steps you
can perform yourself.

## Step 1 — Real options, not memory
- `list_instances` to see what already exists (maybe they already have a suitable one).
- `list_game_versions` to get REAL, live Minecraft versions. NEVER pick a version from
  training memory — it may not exist or may not be latest.
- `search_mods` for any requested mod to get its REAL compatible builds and the loader
  it needs.

## Step 2 — Confirm the essentials with `ask`
Ask only what matters, each as single/multi with concrete options from the steps above:
- Which Minecraft version (from `list_game_versions`)
- Which loader (Fabric for the Sodium family; Forge/NeoForge for big modpacks; Quilt if
  asked) — pick a sensible default
- Which mods/addons (multi, from `search_mods`)

## Step 3 — Create the instance
- `install_loader(gameVersion, loader[, loaderVersion, name])` — this creates the modded
  instance. Tell the user it may take a while (downloads).

## Step 4 — Version isolation (critical, get it right)
The runtime context states the **selected instance** and whether **isolation is ON**:
- Isolation **ON** → that instance's `mods/`, `saves/`, `config/` live under
  `versions/<name>/`. Mods you install affect only that instance.
- Isolation **OFF** → these are SHARED in the base `.minecraft` across ALL versions.
  Version-specific mods installed here will leak into other versions — warn the user.

Always pass `instance = <the target instance>` to `install_mod` so files land in the
right place.

## Step 5 — Install the content
- `install_mod(name/id, instance)` for each chosen mod.
- `install_resourcepack` / `install_shader` / `install_modpack` for those content types.
- Install dependencies too (e.g. Fabric API for most Fabric mods) — `search_mods` will
  reveal them.

## Step 6 — Verify and hand off
- `glob`/`read` the instance's `mods/` dir to confirm the files are present.
- Tell the user it's ready and offer to `launch_instance`.

## Don't
- Don't install Fabric mods onto a Forge instance (or vice-versa).
- Don't skip dependencies.
- Don't forget to pass `instance` to `install_mod` — wrong target = mods "don't work".
