<!-- #BEGIN BLOCK -->
<!-- #PROPERTY NAME=TITLE -->
<div align="center">
    <img src="/HMCL/src/main/resources/assets/img/icon@8x.png" alt="HMCL Logo" width="64"/>
</div>

<h1 align="center">Hello Minecraft! Launcher - Agent Experience</h1>
<!-- #END BLOCK -->

<!-- #BEGIN BLOCK -->
<!-- #PROPERTY NAME=BADGES -->
<div align="center">

[![GitHub](https://img.shields.io/badge/GitHub-repo-blue?style=flat-square&logo=github)](https://github.com/LeeWikie/HMCL-AE)
[![GPLv3](https://img.shields.io/badge/License-GPLv3-blue?style=flat-square)](https://www.gnu.org/licenses/gpl-3.0.html)

</div>
<!-- #END BLOCK -->

---

<!-- #BEGIN LANGUAGE_SWITCHER -->
**English** | 中文 ([简体](README_zh.md), [繁體](README_zh_Hant.md)) | [日本語](README_ja.md) | [español](README_es.md) | [русский](README_ru.md) | [українська](README_uk.md)
<!-- #END LANGUAGE_SWITCHER -->

## Introduction

HMCL-AE is a modified version of [HMCL (Hello Minecraft! Launcher)](https://github.com/HMCL-dev/HMCL) — the open-source, cross-platform Minecraft launcher — with an AI agent assistant integrated into the launcher. The AI can read game files, search for mods, install versions and mods, diagnose crashes, and more, using real tools rather than just chatting.

HMCL-AE inherits all of HMCL's features: mod management (Forge / NeoForge / Fabric / Quilt / OptiFine), modpack support, Java management, account login, Terracotta multiplayer, and cross-platform support (Windows, Linux, macOS, FreeBSD; x86, ARM, RISC-V, MIPS, LoongArch).

**This is beta software under active development.** Expect rough edges. Feedback is welcomed.

## What the AI Agent Can Do

The AI is an **agent with real tools** — it calls tools, gets results, and decides what to do next in a loop, not just chat.

**Tools available:**
- **Filesystem**: `read`, `write`, `edit`, `grep`, `glob` — read crash logs, edit config files, search the game directory.
- **Shell**: run commands (under the configured approval mode).
- **Web**: `web_search` (Tavily / SearxNG), `web_fetch` — look up mod updates, wiki pages.
- **Minecraft operations**: list installed instances & real live versions; search & install mods, resource packs, shaders, modpacks from Modrinth / CurseForge; install a Minecraft version + mod loader fully automatically; launch; rename / delete instances.
- **Crash diagnosis**: matches known crash patterns (reuses HMCL's built-in analyzer).
- **Structured questions** (`ask`): when the agent needs your decision (which version? which mods?) a step-by-step question panel appears above the input field.
- **Self-configuration**: built-in skills (`config-hmcl-ae`, `config-hmcl`) let the agent configure the launcher itself by editing config files.

**What this means in practice:**
- "帮我装个最新版+Fabric，再装Sodium及能用的附属" → the agent lists real versions, searches for Sodium addons, asks which ones you want, installs the version + loader + mods, and reports when it's done.
- "分析一下我的崩溃报告" → reads `crash-reports/`, matches known errors, explains the cause and fix in plain language.
- "把xxx实例改名为yyy" → renames it directly.

## Requirements

- An **AI provider API key** (OpenAI, DeepSeek, Anthropic, or any OpenAI-compatible endpoint). The AI features do **not** work without one — there are no built-in free models.
- Java 8+ (Java 17+ recommended for best performance).
- The launcher itself is free and open source; API usage is billed by the provider you configure.

## Quick Start

1. Download from the [Downloads](#download) section below.
2. Run `HMCL-AE-测试版.exe` (Windows) or `java -jar HMCL-AE-测试版.exe` (Linux / macOS).
3. Go to **AI 助手(Assistant)** → **AI 设置(Settings)** → **服务商(Providers)**, add your API endpoint + key + model name.
4. Start a conversation.

## Download

This is a beta release. Download links:

- [GitHub Releases](https://github.com/LeeWikie/HMCL-AE/releases)
- Windows `.exe` and cross-platform `.jar` (the `.exe` is also a valid jar — `java -jar` works on Linux/macOS).

## Roadmap (near-term)

- **Background install with progress** — downloads currently block the chat. Moving them to HMCL's native task/background system with progress bars is the next priority.
- Account login tools — automate adding Microsoft / offline accounts.
- Java management tools — auto-detect and select the right Java version per instance.
- Conversation editing refinements — action bar visibility on just-sent messages.

## Known Issues

See the included `HMCL-AE 测试版说明与已知问题.md` for an up-to-date, honest list of known issues (in Chinese).

## Contributing

HMCL-AE is a community-driven open-source project (GPLv3). Bug reports, feature requests, and code contributions are welcome.

- [Report issues](https://github.com/LeeWikie/HMCL-AE/issues)
- [Submit pull requests](https://github.com/LeeWikie/HMCL-AE/pulls)

Before contributing code, please read [HMCL's Contributing Guide](./Contributing.md) for build instructions and debug options.

HMCL-AE inherits from HMCL, which has had more than 120 contributors since 2015.

[![Contributors](https://contrib.rocks/image?repo=LeeWikie/HMCL-AE)](https://github.com/LeeWikie/HMCL-AE/graphs/contributors)

## License

Distributed under [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) with the following additional terms:

### Additional terms under GPLv3 Section 7

1. When you distribute a modified version of the software, you must change the software name or the version number in a reasonable way in order to distinguish it from the original version. (Under [GPLv3, 7(c)](https://github.com/HMCL-dev/HMCL/blob/11820e31a85d8989e41d97476712b07e7094b190/LICENSE#L372-L374))

   The software name and the version number can be edited [here](https://github.com/LeeWikie/HMCL-AE/blob/ai-feature/HMCL/src/main/java/org/jackhuang/hmcl/Metadata.java).

2. You must not remove the copyright declaration displayed in the software. (Under [GPLv3, 7(b)](https://github.com/HMCL-dev/HMCL/blob/11820e31a85d8989e41d97476712b07e7094b190/LICENSE#L368-L370))
