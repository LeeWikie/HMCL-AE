---
name: config-hmcl-ae
description: How to configure HMCL-AE itself — add model providers/models, MCP servers, skills, and web search by editing the AE JSON config files. Use whenever the user asks to configure the AI assistant, add a model/provider, set up an MCP server (e.g. "configure tavily-mcp"), install a skill/skill-store (e.g. "install SkillHub, CLI only"), or enable web search.
version: 1.0
---

# Configuring HMCL-AE (the AI assistant configures itself)

HMCL-AE stores its own configuration as JSON files and folders under the launcher home
directory **`.hmcl/`** (the same root your `read`/`write`/`edit` tools already allow). To
change AE's configuration: **read the relevant file, then edit or write it.** Most changes
take effect after the user reopens the AI Settings page or restarts HMCL; skills take
effect after a rescan — tell the user this when you finish.

Always `read` a config file before editing so you preserve existing entries. If a file does
not exist yet, create it with `write`.

## File map (all under `.hmcl/`)

| Concern | File | Shape |
|---|---|---|
| Model providers + global LLM params | `ai-settings.json` | JSON **object** |
| MCP servers | `ai-mcp-settings.json` | JSON **array** |
| Web search | `ai-search-settings.json` | JSON **object** |
| Tool permissions | `ai-tool-permissions.json` | JSON object |
| Skills | `ai-skills/<name>/SKILL.md` | folder + markdown |

Two gotchas:
1. In `ai-settings.json` each provider's `apiKey` is **Base64-encoded on disk**. In
   `ai-search-settings.json` the `apiKey` is **plain text**.
2. `ai-mcp-settings.json` is a JSON **array**; the others are JSON **objects**.

To Base64-encode a key value you can use `shell`:
- Windows (PowerShell): `[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('sk-xxx'))`
- Linux/macOS: `printf %s 'sk-xxx' | base64`

## Add a model provider — `ai-settings.json`

Append an object to the top-level `profiles` array. `id` is any UUID string.
`protocolFamily` is one of `openai-completions`, `openai-reasoning`, `anthropic`.

```json
{
  "id": "<uuid>",
  "displayName": "DeepSeek",
  "protocolFamily": "openai-completions",
  "endpoint": "https://api.deepseek.com",
  "apiKey": "<BASE64-OF-THE-KEY>",
  "defaultModelId": "deepseek-chat",
  "enabled": true,
  "models": [ { "id": "deepseek-chat", "alias": "DeepSeek Chat" } ]
}
```

To make it the active provider, also set the top-level `"selectedProfileId"` to this `id`.

## Add a model to a provider — `ai-settings.json`

Append an entry to that provider's `models` array. Minimum is `{"id": "model-name"}`.
Optional fields (omit or use the sentinel to mean "use default"): `alias`,
`contextWindow` (0=default), `maxOutputTokens` (0=default), `temperature` (-1.0=default),
`reasoningEffort` (""=default), and pricing `inputPricePerMillion` / `outputPricePerMillion`
/ `cacheWritePricePerMillion` / `cacheReadPricePerMillion`. Set the provider's
`defaultModelId` to preselect it.

## Add an MCP server — `ai-mcp-settings.json`

This file is a JSON **array**. Append one object. `transport` is `"stdio"` or `"http"`.

stdio (local command — args go in `command`, split on whitespace):
```json
{ "id": "<uuid>", "displayName": "Tavily", "transport": "stdio",
  "command": "npx -y tavily-mcp", "enabled": true, "autoConnect": true }
```

http (remote Streamable-HTTP/SSE endpoint):
```json
{ "id": "<uuid>", "displayName": "Some MCP", "transport": "http",
  "url": "https://example.com/mcp", "enabled": true, "autoConnect": true }
```

Optional: `allowedTools` (array of tool names; empty = allow all). Discovered tools are
exposed as `mcp.<id>.<toolName>`. To configure an MCP from a project's docs, first
`web_fetch` its README/manifest to learn the exact command/url and required env, then write
the entry. If the server needs an API key in its environment, set it via the `command`
(e.g. `npx ...` with env) per that project's instructions.

## Install / add a skill — `ai-skills/<name>/SKILL.md`

A skill is a folder under `.hmcl/ai-skills/` containing a `SKILL.md` with this frontmatter:
```markdown
---
name: skill-name
description: when to use this skill
version: 1.0
---
# body the agent reads on demand
```
To install a skill from a URL (e.g. a skill store like SkillHub): `web_fetch` the install
page/manifest, follow its instructions, and `write` the `SKILL.md` (and any companion files)
into `.hmcl/ai-skills/<name>/`. If the user says "only install the CLI" (or only a subset),
fetch the manifest, install **only** the requested component, and skip the rest. After
writing, tell the user to click "重新扫描技能目录" (rescan) in AI Settings → 技能与工具,
or restart, so the skill loads.

## Configure web search — `ai-search-settings.json`

Single JSON object. `apiKey` is plain text here. `provider` is a lowercase id such as
`tavily`, `searxng`, `exa`, `bocha`, `zhipu`.
```json
{ "provider": "tavily", "endpoint": "https://api.tavily.com/search",
  "apiKey": "tvly-xxx", "enabled": true, "maxResults": 5 }
```

## After any change

State exactly which file(s) you changed and what you added, and remind the user to reopen
the AI Settings page (or restart HMCL; rescan for skills) for the change to take effect.
Never invent API keys — if a key is required and not provided, ask the user for it.
