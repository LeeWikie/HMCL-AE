---
name: optimize-performance
description: How to make Minecraft run smoother — raise FPS, cut lag/stutter, set memory and install performance mods. Use when the user complains about lag, low FPS, stutter, freezing, or "卡","掉帧","优化","提升性能","太卡了".
version: 1.0
---

# Optimize Minecraft performance

Goal: measurably improve FPS / reduce stutter using HMCL tools, not vague advice.

## Step 1 — Understand the setup
1. `list_instances` — which instance, which Minecraft version, which loader.
2. Check the loader: the Sodium family needs **Fabric** (or NeoForge with the port).
   If the instance is vanilla/Forge-only, decide with the user whether to create a
   Fabric instance for performance.

## Step 2 — Memory (biggest, easiest win for modded)
- Too little RAM causes GC stutter; too much also hurts. For modded, **4–6 GB** max is
  a good default; vanilla needs only ~2 GB.
- Adjust via `edit_instance` (or tell the user the per-instance memory slider).
- Do NOT allocate more than ~half the machine's physical RAM.

## Step 3 — Install performance mods (match the MC version!)
Always `list_game_versions` mentality: use `search_mods` to get the REAL, compatible
build for THIS Minecraft version — never assume a version exists.

Fabric performance stack (install with `install_mod`, passing `instance`):
- **Sodium** — rendering engine (the core FPS boost)
- **Lithium** — general game-logic optimization
- **FerriteCore** — memory usage reduction
- **EntityCulling** — skips rendering hidden entities
- **Iris** (only if the user wants shaders) — shader support on Sodium

Confirm each is compatible with the instance's MC version before installing.

## Step 4 — Quick in-game settings (tell the user)
- Render distance 8–12 (not 32); disable fancy graphics; turn off VSync if input lag.
- These live in `<runDir>/options.txt` — you may read it to report current values.

## Step 5 — Verify
Offer to `launch_instance` and ask the user if FPS improved. If still bad, check for a
heavy mod (shaders, distant horizons) and suggest disabling it.

## Don't
- Don't install Sodium on a non-Fabric instance and expect it to work.
- Don't crank memory to the maximum "just in case".
- Don't recommend OptiFine alongside Fabric performance mods (incompatible).
