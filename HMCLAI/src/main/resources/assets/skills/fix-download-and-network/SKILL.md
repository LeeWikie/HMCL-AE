---
name: fix-download-and-network
description: How to fix download and network problems — slow/failed downloads, stuck progress, mirror/source switching, proxy issues. Use when the user says downloads fail or hang ("下载失败","下载慢","下不动","换源","网络错误").
version: 1.0
triggers: 下载失败, 下载慢, 下载不了, 下不动, 下载卡, 换源, 镜像, 网络错误, 连不上, 超时, download failed, timeout
---

# Fix download & network problems

Goal: identify WHERE the download path breaks (source, network, proxy, disk) and fix the
actual cause — most "download failed" reports are source or network reachability issues.

## Step 1 — What exactly failed?
- A version/loader/mod install? A specific job may still be listed: `list_jobs` /
  `check_job(jobId)` — the FAILED status usually carries the real error (404, timeout, SSL…).
- Read the error, don't guess: 404 = wrong version/file gone; timeout/SSL = network path;
  "space" = disk (`system_info` / runtime context shows free disk).

## Step 2 — The download source (most common fix)
- HMCL downloads through a configurable source: 官方源 (Mojang/官方) vs 镜像源 (BMCLAPI).
  Inside China the official source is often slow/unstable; the mirror is usually faster —
  and vice versa: BMCLAPI occasionally lags behind for brand-new versions.
- Switching: the `config-hmcl` skill covers the launcher's downloadType setting — read that
  skill if you need the file details; the user can also switch in 设置 → 下载 → 下载源.
- After switching, retry the SAME install once. Do not loop retries.

## Step 3 — Network path
- Proxy: if the user uses one, HMCL has proxy settings (设置 → 网络); a dead proxy set there
  breaks ALL downloads — check that first when everything fails.
- Test reachability honestly: `web_fetch` a known URL (e.g. the mirror's root) to distinguish
  "this machine is offline" from "this one source is down".
- Mod platforms: CurseForge/Modrinth may be blocked or slow in some regions — if `search_mods`
  works but downloads fail, say which platform failed and try the other source when the tool
  supports it (`update_mod(source=...)`).

## Step 4 — Retry rules
- Retry a transient failure ONCE after fixing something (source switched, proxy fixed).
- If it still fails, STOP and report: what was tried, the exact error, and what the user can
  check (their connection, VPN state, disk space). Never claim success without a tool result.

## Don't
- Don't hammer retries in a loop — it wastes minutes and hides the real cause.
- Don't download game files via shell (curl/wget) — installs must go through HMCL's tools so
  hashes/paths stay correct.
