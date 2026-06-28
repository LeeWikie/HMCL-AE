---
name: config-hmcl
description: How to read and modify native HMCL launcher configuration — instance memory/Java/JVM args, game directories, launcher theme/background, proxy — by editing HMCL's JSON config files. Use when the user asks to change Minecraft memory, Java path, window size, auto-join server, game directory, launcher theme/background, or proxy.
version: 1.0
---

# Configuring native HMCL (launcher & instance settings)

HMCL stores its settings as schema-versioned JSON files. To change them: `read` the file,
then `edit`/`write` it. Every file has a `"$schema"` key like
`https://schemas.glavo.site/hmcl/<name>/1.0.0` — **keep it intact** or HMCL will treat the
file as invalid and discard your edits.

## ⚠️ Most important caveat — you are running inside HMCL

HMCL holds its **launcher-level** settings in memory and **rewrites them on exit**, so a
manual edit to `launcher-settings.json` / `game-settings.json` made while HMCL is running
will be silently overwritten. For launcher-level changes, either (a) do it through the HMCL
UI, or (b) tell the user to close HMCL, then edit, then reopen. **Per-instance** settings
(`instance-game-settings.json`) are read when the game launches, so editing them while HMCL
is open is safe as long as that instance isn't mid-launch. Always tell the user which case
applies and that a restart/relaunch is needed to take effect.

## File locations (`<.hmcl>` = launcher home, usually `<launch dir>/.hmcl`)

| Concern | File |
|---|---|
| Launcher settings (theme, proxy, language, background, download) | `<.hmcl>/config/launcher-settings.json` |
| Shared user settings (Java list, log retention) | `%APPDATA%/.hmcl/config/user-settings.json` |
| Game directories / profiles (local) | `<.hmcl>/config/game-directories.json` |
| Shared game settings presets ("global" game settings) | `<.hmcl>/config/game-settings.json` (`presets` array) |
| Per-instance overrides | `<gameDir>/versions/<ver>/.hmcl/config/instance-game-settings.json` |

`<gameDir>` is the profile path (default `.minecraft`). Use `read`/`glob` to locate the
exact version folder under `<gameDir>/versions/`.

## Per-instance game settings — the override rule (critical)

`instance-game-settings.json` only applies a field if that field's name is also listed in
its `"overrideProperties"` array; otherwise the value inherits from the `"parent"` preset.
**You must set BOTH the value AND add its name to `overrideProperties`.**

Change an instance's max memory and Java:
```json
{
  "$schema": "https://schemas.glavo.site/hmcl/instance-game-settings/1.0.0",
  "parent": "game-settings-preset:<uuid>",
  "autoMemory": false,
  "maxMemory": 8192,
  "javaType": "CUSTOM",
  "customJavaPath": "C:\\Program Files\\Java\\jdk-21\\bin\\javaw.exe",
  "overrideProperties": ["autoMemory", "maxMemory", "javaType", "customJavaPath"]
}
```
Notes: memory is in **MiB**; `autoMemory:true` ignores `maxMemory`, so set it false and
override it. Java: only `javaType:"CUSTOM"` + `customJavaPath` is hand-editable (the
`DETECTED` mode uses a path hash you can't easily write). Escape backslashes in Windows
paths. To change these **globally**, edit the matching object in `game-settings.json`'s
`presets` array instead (no `overrideProperties` needed there).

Other useful `GameSettings` keys (same file): `minMemory`, `jvmOptions`, `gameArguments`,
`environmentVariables`, `windowType` (`WINDOWED`/`FULLSCREEN`/`MAXIMIZED`), `width`,
`height`, `runningDirectory`, `launcherVisibility` (`CLOSE`/`HIDE`/`KEEP`/`HIDE_AND_REOPEN`),
`quickPlayMultiplayer` (auto-join server address), `graphicsBackend`
(`DEFAULT`/`OPENGL`/`VULKAN`).

## Launcher settings — `launcher-settings.json` (edit while HMCL is closed)

- Theme: `"themeBrightness"` (`"light"`/`"dark"`), `"themeColor"` (`blue`/`darker_blue`/
  `green`/`orange`/`purple`/`red`, or hex `#RRGGBB`).
- Background: `"backgroundType"` (`DEFAULT`/`CUSTOM`/`CLASSIC`/`NETWORK`/`PAINT`),
  `"backgroundImage"` (local path), `"backgroundImageUrl"`, `"backgroundOpacity"` (0–1).
- Language: `"language"` (e.g. `zh`, `en`). Font: `"launcherFontFamily"`.
- Download: `"downloadThreads"`, `"versionListSource"`/`"fileDownloadSource"`
  (`DEFAULT`/`OFFICIAL`/`MIRROR`).
- Proxy: `"proxyType"` (`SYSTEM`/`DIRECT`/`HTTP`/`SOCKS`), `"proxyHost"`, `"proxyPort"`,
  and for auth `"hasProxyAuth":true`, `"proxyUser"`, `"proxyPassword"`.
- Selections like `selectedGameDirectory`, `selectedInstance`, `selectedAccount` are typed
  IDs (`game-directory:<uuid>`, etc.) owned by HMCL — change via the UI, not by hand.

## Game directories — `game-directories.json`

Top-level `"directories"` array; each entry: `{"id":"game-directory:<uuid>", "path":"...",
"name":"..."}`. Relative paths go in this file; **absolute** paths go in
`user-game-directories.json` (under `%APPDATA%/.hmcl/config/`). To add a directory, append an
entry with a fresh UUID id.

## After any change

State which file(s) you changed. For launcher-level files, remind the user they must be
applied with HMCL closed (or done via the UI) and a restart. For instance settings, remind
them to relaunch that instance. Never fabricate account credentials or IDs.
