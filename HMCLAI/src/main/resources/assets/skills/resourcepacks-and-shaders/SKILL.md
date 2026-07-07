---
name: resourcepacks-and-shaders
description: How to install resource packs and shaders correctly — pick packs for the user's version, install the shader loader (Iris/OptiFine) when missing, and enable them in-game. Use when the user mentions shaders, resource packs or textures ("光影","材质包","资源包").
version: 1.0
triggers: 光影, 材质包, 资源包, 材质, 画质包, 皮肤包, shader, shaders, resource pack, texture pack, iris
---

# Resource packs & shaders

Goal: the pack/shader actually shows up in-game — which means matching the game version AND
having the right loader mod for shaders.

## Tools
- `search_resourcepacks` / `install_resourcepack(name/id, instance)` — resource packs.
- `search_shaders` / `install_shader(name/id, instance)` — shader packs.
- `list_resourcepacks(instance)` — what's already installed.
- `instance_details(instance)` — version + loader (needed to judge shader support).
- `install_mod` — for the shader LOADER (Iris) when missing.

## Shaders — the critical dependency
A shader pack does NOTHING without a shader loader:
1. `instance_details` — check loader.
   - **Fabric/Quilt** → needs **Iris** (+ Sodium, they pair): `install_mod("iris", instance)`,
     `install_mod("sodium", instance)` if absent (`list_mods` first).
   - **NeoForge/Forge (new versions)** → Iris ports (e.g. "Iris/Oculus" family) — search and
     pick the one matching the loader+version; be honest if none exists for that combo.
   - **OptiFine route** (older/vanilla setups): OptiFine is an alternative but conflicts with
     Sodium/Iris — never install both stacks into one instance.
2. Then `install_shader(<pack>, instance)` — packs land in `shaderpacks/`.
3. Tell the user where to enable it: 视频设置 → 光影 (with Iris) / Shaders (OptiFine).

## Resource packs — simpler but version-sensitive
1. `install_resourcepack(<pack>, instance)` — lands in `resourcepacks/`.
2. Pack format must roughly match the game version; if the pack is for another version the
   game shows it as incompatible (red) — it may still work, warn the user.
3. Enable in-game: 选项 → 资源包.

## Performance warning
Shaders are heavy. If the user earlier complained about lag, say so and suggest a light
shader (or none) — don't silently install a heavy pack on weak hardware (`system_info` if
you need to check RAM/GPU).

## Don't
- Don't install a shader pack without confirming the loader chain exists.
- Don't mix OptiFine with Sodium/Iris in one instance.
- Don't unzip packs manually via shell — the install tools place them correctly.
