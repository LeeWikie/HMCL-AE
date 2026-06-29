---
name: manage-accounts
description: How to log in, add, or switch Minecraft accounts (Microsoft / offline). Use when the user wants to log in, sign in, add or change account, "登录","账号","换号","微软登录","离线账号","正版登录".
version: 1.0
---

# Manage Minecraft accounts

Use the dedicated account tools — **never** attempt login via shell, browser scripting,
or editing files. HMCL owns the account store and the OAuth flow.

## Tools
- `list_accounts` — show logged-in accounts (username, type, UUID, which is active)
- `microsoft_login` — open HMCL's native Microsoft sign-in dialog (the real OAuth flow)
- `add_offline_account(username)` — create an offline (no-server-auth) account
- `select_account(username)` — make an account the active one

## Decide which type
- **Microsoft (genuine / 正版)**: required to play online-mode servers, Realms, and to
  use the player's real skin/name. Use `microsoft_login`.
- **Offline (离线)**: no Microsoft account needed; a chosen username only. CANNOT join
  online-mode servers or Realms. Use `add_offline_account`. Only suggest this if the
  user explicitly wants offline / has no Microsoft account.

## Workflows

### Log in with Microsoft
1. `microsoft_login` — this opens the native dialog and starts the browser OAuth.
2. Tell the user: "已打开登录窗口，请在弹出的窗口里完成微软登录。" (You cannot complete
   it for them — it's interactive and secure.)
3. After they say it's done, `list_accounts` to confirm the new account appears.

### Add an offline account
1. Ask for the desired username if not given (`ask`, single/multi with a sensible default
   is not possible here — just confirm the name in plain text or proceed if provided).
2. `add_offline_account(username)` — it becomes active by default.

### Switch the active account
1. `list_accounts` to get exact names.
2. `select_account(username)`.

## Don't
- Don't run any shell/auth command or edit account JSON to "log in" — it will fail.
- Don't create an offline account when the user asked for Microsoft/正版.
- Don't claim a Microsoft login succeeded until `list_accounts` shows it.
