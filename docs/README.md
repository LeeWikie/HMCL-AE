<div align="center">
  <img src="../HMCL/src/main/resources/assets/img/icon@8x.png" alt="HMCL-AE" width="80"/>

  # HMCL-AE

  **A fork of HMCL with an integrated AI agent that executes Minecraft launcher operations via tool calling.**

  [Releases](https://github.com/LeeWikie/HMCL-AE/releases/latest) · [Changelog](../CHANGELOG.md) · [Issues](https://github.com/LeeWikie/HMCL-AE/issues) · [Upstream HMCL](https://github.com/HMCL-dev/HMCL) · [简体中文](../README.md)

  <sub>GPLv3 · Java 17+ · Windows / Linux / macOS / FreeBSD · v0.1.1-alpha</sub>
</div>

---

## Overview

HMCL-AE targets Minecraft players and modpack maintainers who bring their own LLM API key and want to automate repetitive launcher tasks (installing versions and mod loaders, managing mods and resource packs, diagnosing crashes, editing configs and saves). Built on top of [HMCL](https://github.com/HMCL-dev/HMCL), it integrates a native function-calling agent that invokes HMCL's internal APIs directly—reusing HMCL's download providers, mirrors, authentication system, and Java runtime management.

### What Is New in HMCL-AE vs. Upstream HMCL

- **Added by HMCL-AE**:
  - `HMCLAI/` standalone Gradle subproject: UI-free agent core (session & context compaction, LangChain4j adapters, tool registry & execution policy, guardrails & structured error envelopes, deterministic skill matching, MCP client, web search clients, append-only JSONL trace recorder, and bundled model metadata library).
  - `HMCL/src/main/java/org/jackhuang/hmcl/ui/ai/`: native JavaFX chat and settings pages, CommonMark-based Markdown renderer, background job progress UI, Minecraft domain tools wired to HMCL's APIs, and OCR clients.
  - Build/release additions: LangChain4j, CommonMark, RichTextFX, and TestFX dependencies, `-PheadlessTest` flag, and `.github/workflows/ae-release.yml`.
- **Inherited from Upstream HMCL**:
  - `HMCLCore/`, `HMCLBoot/`, `minecraft/libraries/`, and the rest of `HMCL/`: mod loader installation (Forge / NeoForge / Fabric / Quilt / OptiFine), modpack handling, Java management, Microsoft/offline/authlib-injector accounts, Terracotta multiplayer, and cross-platform/multi-architecture support.

## Installation and Usage

### Prebuilt Binaries

1. Download the build for your platform (`.exe` / `.jar` / `.sh` / `.deb`) from [Releases](https://github.com/LeeWikie/HMCL-AE/releases/latest).
2. Run `HMCL-<version>.exe` on Windows, or `java -jar HMCL-<version>.jar` on Linux / macOS / FreeBSD (requires Java 17+; Java 21+ recommended).
3. Configure a model in the launcher sidebar under **AI Assistant -> AI Settings -> Model Providers** with your endpoint, API key, and model ID.

Note: AI features require your own API key; no built-in free models are provided. Supported protocols include OpenAI (Completions / Reasoning), Anthropic Messages API, and OpenAI-compatible endpoints (DeepSeek, Zhipu, Moonshot, etc.).

### Build and Run from Source

Requires JDK 17+ with JavaFX (CI uses Zulu JDK 17 `jdk+fx`).

```bash
git clone https://github.com/LeeWikie/HMCL-AE.git
cd HMCL-AE
./gradlew :HMCL:run                # Compile and launch the application
./gradlew :HMCL:makeExecutables    # Build .jar / .exe / .sh into HMCL/build/libs/
./gradlew :HMCL:makeDeb            # Optional: build Debian package (.deb)
```

## Architecture and Engineering Highlights

The repository is organized into four main modules: `HMCLAI` (UI-free AI core), `HMCL` (launcher UI and Minecraft domain tools), `HMCLCore` (game repository, downloads, crash analyzer), and `HMCLBoot` (bootstrapper).

- **Domain-Facade Tooling and Runtime Hot-Toggle**:
  - `HMCL/src/main/java/org/jackhuang/hmcl/ui/ai/AIMainPage.java` registers **28** top-level built-in tools: 23 always-on tools plus 5 hot-toggled tools (`shell`, `web_fetch`, `web_search`, `ocr_image`, `nbt`) bound via `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/tools/ToggleToolsBinder.java` so settings changes take effect immediately without a restart.
  - To keep tool-schema token overhead manageable, Minecraft capabilities are grouped into 6 domain facades—`instance` (39 local instance/mod/world/backup/pack/shader/modpack/Java actions), `game` (3 runtime actions), `search` (6 external search actions querying Modrinth and CurseForge concurrently), `account` (5 account and skin actions), `job` (3 background job actions), and `nbt` (6 save NBT and player-data transfer actions)—dispatching **62** concrete operations.
  - External MCP servers are supported over stdio and Streamable HTTP via `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/mcp/McpClientManager.java`, with `mcp.<serverId>.` name prefixing and per-server tool allowlists.
- **Guardrails, Approval Policy, and Failure Contracts**:
  - `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/tools/AiExecutionPolicy.java` implements `Manual` / `Auto` / `yolo` approval modes plus an orthogonal `Plan` mode (read-only investigation that blocks all writes), alongside a non-bypassable hard block (`BlockReason.UNATTENDED_DANGEROUS`) on dangerous writes during unattended turns (such as automatic follow-up turns after background jobs finish).
  - `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/tools/ToolFailures.java` standardizes tool errors into a structured envelope carrying real in-scope candidates, `Retryable: yes|no|later`, and a concrete next action. `TerminalDenialRegistry.java` short-circuits repeated calls after terminal user denials.
  - `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/tools/ReadLedger.java` enforces a read-before-edit contract with real-path resolution and byte-level SHA-256 staleness checks. `DangerousCommands.java` and `CriticalOperations.java` guard against destructive shell commands (including blocking termination of `java` / `javaw` or the launcher process) and high-risk save edits.
- **On-Demand Skills and Model Metadata Catalog**:
  - `HMCLAI/src/main/resources/assets/skills/` bundles **32** `SKILL.json` playbooks matched deterministically by `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/skills/SkillMatcher.java` (NFKC normalization, word boundaries for ASCII triggers) rather than injecting all instructions into the system prompt.
  - `HMCLAI/src/main/resources/assets/ai/model-library.json` bundles **1,604** model metadata entries (context window, max output, modalities, pricing, tool/vision/reasoning flags), resolved by `ModelLibrary.java` via exact match and conservative normalization (lowercasing and stripping `vendor/` prefixes). `ChatAgent.java` triggers automatic conversation compaction near 90% context usage.
- **Web Search, OCR, and Diagnostics**:
  - Web search (`WebSearchTool.java`) wires 4 providers: Tavily, SearXNG, Bocha, and Zhipu. Screenshot OCR (`OcrProvider.java`) wires 5 backends: OCR.space, Vision LLM (OpenAI-compatible), Baidu OCR, Google Cloud Vision, and local Umi-OCR.
  - `HMCLAI/src/main/java/org/jackhuang/hmcl/ai/trace/TraceRecorder.java` writes optional per-session append-only JSONL traces, scrubbing secrets through `Redactor.java` before disk write.
  - `HMCLAI/build.gradle.kts` pins `jackson-databind` and `jackson-core` to `2.21.5` to patch CVE-2026-54512 / 54513 / 54515 pulled transitively by LangChain4j.

## Verification and CI

Tests use JUnit 5, Jimfs, and TestFX:

- `HMCLAI/src/test/`: **90** test classes covering the agent loop and compaction, approval/guardrail policies, error envelopes, `ReadLedger` file contracts, terminal denial short-circuiting, trace recording and redaction, MCP, skill matching, and the model library.
- `HMCL/src/test/java/org/jackhuang/hmcl/ui/ai/`: **85** test classes covering Minecraft domain tools, TestFX UI tests, and hardcoded CJK string checks.
- `HMCLCore/src/test/` and upstream `HMCL/src/test/`: 64 additional test classes.

Commands:

```bash
./gradlew :HMCLAI:test                    # Run UI-free HMCLAI unit tests
./gradlew test -PheadlessTest             # Run all non-GUI tests across modules (TestFX tests self-skip)
./gradlew test                            # Run the full suite including TestFX UI tests (requires display)
./gradlew checkstyle checkTranslations    # Run Checkstyle and translation validation
```

GitHub Actions workflows:

- `.github/workflows/gradle.yml`: runs `./gradlew build --no-daemon --parallel` on Ubuntu with Zulu JDK 17 (`jdk+fx`) and uploads `.jar` / `.exe` / `.sh` / `.deb` artifacts.
- `.github/workflows/check-codes.yml`: runs `./gradlew checkstyle checkTranslations` on `.java` / `.properties` changes (note: `build.gradle.kts` currently excludes `org/jackhuang/hmcl/ai/**` and `org/jackhuang/hmcl/ui/ai/**` from Checkstyle).
- `.github/workflows/ae-release.yml`: manual release workflow that builds executables and publishes a GitHub Release with `docs/release-notes/` and a SHA-256 checksum table.

## Status and Known Limitations

- **Alpha stage**: Currently **0.1.1-alpha** (versioning reset at `0.1.0-alpha`; earlier `0.x-beta` builds are deprecated). Internal agent behavior is still evolving and breaking changes may occur.
- **Destructive operations**: The agent can download, install, modify/delete files, and edit/delete world saves. Back up important saves before use and review confirmation dialogs carefully.
- **Known limitations**:
  - While `AiJobManager` and the `job` tool provide background task infrastructure, most install/download tools still execute synchronously by default, blocking the chat turn until completion.
  - Multi-step reliability varies significantly across LLMs; stronger tool-calling models follow workflows more consistently.
  - Modpack export currently supports offline Modrinth `.mrpack` output only; datapack management supports listing and local `.zip` installation, not remote search.
  - Cross-session memory tools (`remember` / `recall`) are implemented in code but currently disabled at the product gate.
  - Color emoji rendering is opt-in in chat settings (downloads Noto Emoji assets); system rendering is used by default.

## License and Attribution

Distributed under **GPLv3** (see [LICENSE](../LICENSE)), including upstream HMCL's Section 7 additional terms:

1. Modified versions must change the software name or version number reasonably to distinguish them from upstream HMCL (configured in [`HMCL/src/main/java/org/jackhuang/hmcl/Metadata.java`](../HMCL/src/main/java/org/jackhuang/hmcl/Metadata.java)).
2. Copyright declarations displayed in the software must not be removed.

HMCL-AE is a fork of [HMCL](https://github.com/HMCL-dev/HMCL). Upstream copyright notices are preserved, and credit is due to HMCL and all its contributors since 2015.

## Contact

- Author: LeeWikie (Neryc)
- Email: me@neryc.net
- Issues: [GitHub Issues](https://github.com/LeeWikie/HMCL-AE/issues)

> Note: Other translated README files under `docs/` (`README_zh.md`, `README_ja.md`, etc.) are unsynchronized upstream HMCL originals and do not cover HMCL-AE's AI features.
