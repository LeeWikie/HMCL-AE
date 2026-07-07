---
name: java-and-memory
description: How to fix Java-version and memory problems — wrong Java for the Minecraft version, downloading the right runtime, setting instance memory, OutOfMemory crashes. Use when the user mentions Java or memory ("java不对","内存","爆内存","OutOfMemory").
version: 1.0
triggers: java, jdk, jre, 内存, 爆内存, 内存溢出, 内存不足, 堆内存, outofmemory, oom, 分配内存, 改内存
---

# Java & memory

Goal: the right Java for the game version, and a sane memory setting — the two most common
"game won't even start" causes after mods.

## Java version rules (memorise these)
- Minecraft **≤1.16.x** → Java 8 (some builds tolerate 11).
- **1.17–1.17.1** → Java 16+.
- **1.18–1.20.4** → Java 17+.
- **1.20.5+ / 1.21+** → Java 21+.
- Forge for old versions is strict about Java 8; modern NeoForge follows the game's rule.

## Diagnose
1. `list_java` — what runtimes HMCL has discovered (version + path).
2. `instance_details(instance)` — the game version → required Java from the table above.
3. A "wrong Java" failure shows up as an immediate crash with `UnsupportedClassVersionError`
   or a launcher warning; `diagnose-crash` handles the log side, this skill fixes the cause.

## Fix
- Missing suitable runtime → `download_java` (pick the version from the table) — it runs in
  the background; `check_job` before claiming done. HMCL auto-selects a suitable Java per
  instance by default; only pin a specific one when auto-selection picked wrong (per-instance
  Java is a native instance setting — the `config-hmcl` skill covers the file details).

## Memory
- `set_instance_memory(instance, megabytes)` — per-instance allocation.
- Sane defaults: vanilla 2–4 GB; modded 4–6 GB; heavy packs 6–8 GB. More is NOT better —
  an oversized heap causes GC stutter; never allocate beyond ~70% of physical RAM
  (check the runtime context / `system_info`).
- **OutOfMemory crash**: raise memory ONE step (e.g. 4096 → 6144) and verify. If it still
  OOMs with plenty allocated, a mod is leaking — go the `diagnose-crash` route instead of
  raising memory forever.
- 32-bit Java caps around 1.5 GB — if `list_java` shows 32-bit only, download a 64-bit one.

## Don't
- Don't edit JVM args by hand for memory — `set_instance_memory` is the tool.
- Don't allocate "all the RAM" — explain the GC-stutter tradeoff in one sentence.
- Don't install Java via shell/winget — `download_java` keeps it managed by HMCL.
