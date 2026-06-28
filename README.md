<div align="center">
    <img src="HMCL/src/main/resources/assets/img/icon@8x.png" alt="HMCL-AE" width="72"/>
</div>

<h1 align="center">HMCL-AE</h1>
<p align="center"><b>Hello Minecraft! Launcher — Agent Experience</b></p>
<p align="center">在 Minecraft 启动器里嵌入<b>真正会用工具干活</b>的 AI agent —— 装机、修崩溃、管 mod,不只是聊天。</p>

<p align="center">
<a href="https://www.gnu.org/licenses/gpl-3.0.html"><img src="https://img.shields.io/badge/License-GPLv3-blue?style=flat-square" alt="GPLv3"/></a>
<img src="https://img.shields.io/badge/Java-8+-orange?style=flat-square" alt="Java 8+"/>
<img src="https://img.shields.io/badge/Platform-Win%20%7C%20Linux%20%7C%20macOS%20%7C%20FreeBSD-green?style=flat-square" alt="Platform"/>
<a href="https://github.com/LeeWikie/HMCL-AE/releases"><img src="https://img.shields.io/badge/Release-v0.1.0--beta-purple?style=flat-square" alt="Release"/></a>
</p>

---

## 这是什么

HMCL-AE 是 [HMCL（Hello Minecraft! Launcher）](https://github.com/HMCL-dev/HMCL) 的修改版 —— 在上游全部启动器功能之外,**加了一套真正会调用工具的 AI agent**。

和"嵌个聊天框接 API"不同,这个 agent:

- 有 **15+ 个工具**:读写文件、搜网页、搜/装 mod、安装 Minecraft 版本和加载器、改名/删除实例、跑 shell、向你提问……
- **循环执行**:调工具 → 拿结果 → 决定下一步 → 再调,直到任务完成(最多 25 轮)。
- **会自我纠正**:工具失败时错误回传给模型,它知道哪步坏了、能换条路,而不是闷头胡说。

它继承 HMCL 全部功能 —— 模组管理(Forge / NeoForge / Fabric / Quilt / OptiFine)、整合包、Java 管理、账号登录、Terracotta 联机、跨平台(Windows / Linux / macOS / FreeBSD;x86 / ARM / RISC-V / MIPS / LoongArch)。

> ⚠️ **这是测试版(beta),仍在密集开发中。** 需要自己配 API key,启动器本身免费开源。

---

## 它能干什么

### 🚀 装 Minecraft,全自动

> 「帮我装个最新版 + Fabric,再装 Sodium 和能用上的附属」

AI 会:列现有实例 → 实时拉取真实版本列表(不靠记忆猜)→ 搜 Sodium 及附属 → **弹分步向导让你选**(版本?加载器?哪些附属?)→ 自动装版本+Fabric → 依次装你选的 mod → 报告完成。

### 🩺 诊断崩溃

> 「我的 Minecraft 崩了」

AI 会:读 `logs/latest.log` → 找最新崩溃报告 → 用 HMCL 内置规则库(CrashReportAnalyzer,20+ 种常见崩溃)匹配 → 用中文告诉你原因和解决办法。

### 🧰 管理一切

搜/装 mod、资源包、光影、整合包(Modrinth + CurseForge)、改名/删除实例、改配置 —— 都是 AI 直接动手,不是甩给你"请手动点这些步骤"。

---

## 工具一览

| 类别 | 工具 |
|---|---|
| **文件/命令** | `read` `write` `edit` `grep` `glob` `shell` |
| **网络** | `web_search`(Tavily/SearXNG) `web_fetch` |
| **MC 操作** | `list_instances` `list_game_versions` `search_mods` `install_mod` `install_loader` `launch_instance` `edit_instance` `delete_instance` `search/install_resourcepack` `search/install_shader` `search/install_modpack` `search_worlds` `match_known_errors` |
| **对话** | `ask`(分步提问向导) `sleep` `resolve_game_context` |
| **自我配置** | 内置 `config-hmcl-ae` / `config-hmcl` 技能,让 AI 改自身和 HMCL 的配置 |

---

## 安全机制(不是摆设)

安全策略**真接进了工具执行路径**(不是只写在提示词里):

- **危险命令拦截**:`rm -rf`、`format`、`shutdown`、`reg delete` 等 15+ 种危险 shell 命令,执行前**弹窗让你确认**。
- **安全模式**:读/写/联网自动放行,**只有危险操作才拦**——不是每次调工具都问。
- **三档审批**:安全(默认) / 询问 / 全开(YOLO)。

---

## 会话编辑

每条消息气泡下方一行小图标:

- **你的消息**:复制 · 编辑 · 重发 · 分支 · 删除
- **AI 的消息**:复制 · 重生成 · 分支 · 删除

支持把对话从某条消息**分支**到新会话、**删除**单条消息让它从上下文消失、**编辑**后重发。

---

## 怎么用

### 1. 下载
从 [Releases](https://github.com/LeeWikie/HMCL-AE/releases/latest) 下载。

### 2. 运行
- **Windows**:双击 `.exe`
- **Linux / macOS**:`java -jar HMCL-AE-xxx.exe`(这个 exe 同时也是合法 jar)

### 3. 配 AI
进入侧栏 **「AI 助手」** → **「AI 设置」→「服务商」** → 添加,填:
- API 端点(如 `https://api.deepseek.com`)
- API Key
- 模型名(如 `deepseek-chat`)

支持 OpenAI / DeepSeek / Anthropic 或任何 OpenAI 兼容端点。

### 4. 开始
直接打字,AI 会自己调工具。中文对话效果更好。

---

## 已知局限(诚实列出)

1. **安装/下载是阻塞式的**,下载期间聊天会"卡住"、没有进度条 —— 这是**下一个最优先解决的问题**。
2. **不同模型差距大**:强模型(Claude Opus 4.8 / GPT-4.1 / DeepSeek-R1)执行复杂流程更稳。
3. 账号登录(微软 OAuth/离线)、Java 自动配置、整合包导出 —— 工具未做。
4. 彩色 emoji 需在聊天设置里手动开启 + 联网下载(Noto);默认走系统渲染。
5. 数据包(datapack)管理未做。

---

## 从源码构建

```bash
git clone https://github.com/LeeWikie/HMCL-AE.git
cd HMCL-AE
./gradlew :HMCL:run                # 运行
./gradlew :HMCL:makeExecutables    # 构建 .exe / .jar / .sh 到 HMCL/build/libs/
```

模块:**HMCLAI**(AI 核心:agent 循环、工具、搜索、skills、LangChain4j 适配)· **HMCL**(启动器 + AI UI + HMCL 操作工具)· **HMCLCore**(游戏仓库/下载/崩溃分析)· **HMCLBoot**(引导)。

---

## 许可证

**GPLv3**,附加条款(Section 7):分发修改版时须修改软件名/版本号以区分原版;须保留版权声明。

HMCL-AE 是 [HMCL](https://github.com/HMCL-dev/HMCL) 的修改版,保留原始版权声明。感谢自 2015 年以来 120+ 位 HMCL 贡献者。

---

<p align="center">
<a href="https://github.com/LeeWikie/HMCL-AE/releases">下载</a> ·
<a href="https://github.com/LeeWikie/HMCL-AE/issues">反馈问题</a> ·
<a href="https://github.com/HMCL-dev/HMCL">上游 HMCL</a>
</p>

<details>
<summary>其它语言文档 / Other languages</summary>

[English](docs/README.md) · [简体中文](docs/README_zh.md) · [繁體中文](docs/README_zh_Hant.md) · [日本語](docs/README_ja.md) · [español](docs/README_es.md) · [русский](docs/README_ru.md) · [українська](docs/README_uk.md)

</details>
