---
name: diagnose-crash
description: How to diagnose a Minecraft crash or launch failure end-to-end and fix it. Use whenever the user says the game crashed, won't start, closed immediately, black screen, or shares an error/crash report ("游戏崩溃了","进不去","闪退","打不开","报错").
version: 1.0
---

# Diagnose a Minecraft crash / launch failure

Goal: find the real cause from the logs, explain it in plain language, and **fix it
yourself** when you can. Never just dump the log back at the user.

## Step 1 — Gather the evidence (read, don't guess)

1. `list_instances` — know which instance is selected and where its run dir is.
2. Read the latest log: `read` `<runDir>/logs/latest.log`. If huge, `grep` it for
   `Exception`, `Error`, `Caused by`, `at net.`, `Mixin`, `OutOfMemory`.
3. Find the newest crash report: `glob` `<runDir>/crash-reports/*.txt`, then `read` the
   most recent one.
4. Run `match_known_errors` on the most relevant error text — HMCL has a built-in
   rule base (20+ common crashes) that often names the exact cause.

## Step 2 — Identify the cause

Common patterns and the fix to apply:

| Symptom in log | Likely cause | Fix |
|---|---|---|
| `java.lang.OutOfMemoryError`, `GC overhead` | Not enough RAM allotted | Raise max memory via `edit_instance` (or tell the user the slider). 4–6 GB is typical for modded. |
| `Incompatible magic value`, wrong Java version, `UnsupportedClassVersion` | Wrong Java | `list_java`; modern MC needs Java 17/21. Suggest the right runtime. |
| `Mixin apply failed`, `because mod X`, a mod name in the stack | One mod crashes / conflicts | Identify the mod file in `<runDir>/mods`; disable it by renaming `.jar`→`.jar.disabled` (shell rename), or remove it. |
| `missing dependency`, `requires fabric api` | Missing dependency mod | `search_mods` the dependency and `install_mod` it. |
| `Failed to download`, asset/library errors | Broken/incomplete install | Re-install the loader/version; check network. |
| Loader version mismatch (Forge/Fabric vs MC) | Loader/MC mismatch | Re-create the instance with `install_loader(gameVersion, loader)`. |

If `match_known_errors` returns a hit, trust it and explain that rule's cause/fix.

## Step 3 — Fix and confirm

- Apply the concrete fix with tools (toggle/remove a mod, adjust memory, install a
  dependency, fix Java). Confirm anything destructive first.
- Tell the user, in plain language: what was wrong, what you changed, and offer to
  `launch_instance` to verify.
- If you genuinely cannot fix it (e.g. corrupt save, hardware/driver issue), explain
  clearly and give the smallest next step — don't hand over a wall of log text.

## Don't
- Don't ask the user to read logs themselves — you have read/grep.
- Don't guess the cause from the title alone; read the actual stack trace.
- Don't reinstall everything as a first resort.
