<div align="center">
  <img src="HMCL/src/main/resources/assets/img/icon@8x.png" alt="HMCL-AE" width="80"/>

  # HMCL-AE

  **会动手的 Minecraft 启动器。**

  在 HMCL 里内置一个真正调用工具干活的 AI —— 装版本、配模组、修崩溃，它自己做，不只是陪你聊。

  [下载](https://github.com/LeeWikie/HMCL-AE/releases/latest) · [反馈](https://github.com/LeeWikie/HMCL-AE/issues) · [上游 HMCL](https://github.com/HMCL-dev/HMCL)

  <sub>GPLv3 · Java 8+ · Windows / Linux / macOS / FreeBSD · v0.1.0-beta</sub>
</div>

---

## 它能做什么

跟它说一句话，剩下的交给它：

> **「帮我装个最新版，配上 Fabric 和 Sodium 全家桶」**
> 它会拉取真实版本列表 → 弹分步选择让你确认 → 装好版本和加载器 → 依次装好模组 → 告诉你可以开玩了。

> **「我游戏崩了」**
> 它会读日志和崩溃报告 → 比对内置崩溃规则库 → 用大白话告诉你哪坏了、怎么修，能修的直接帮你修。

> **「太卡了」**
> 它会看你的实例和配置 → 调内存、装性能模组、给出画面设置建议。

装版本 / 配模组 / 诊断崩溃 / 管理实例与账号 / 优化性能 —— 都是它直接操作，而不是甩给你一串手动步骤。

## 它和「套个聊天框」有什么不同

- **真会用工具**：40+ 个工具，覆盖文件、模组、版本、加载器、实例、世界、账号、Java、系统信息、联网搜索等。
- **循环执行**：调工具 → 看结果 → 决定下一步 → 再调，直到把事做完。
- **会自我纠正**：工具报错会回传给模型，它知道哪步坏了、换条路走，而不是硬编。
- **复用启动器本体**：所有操作走 HMCL 原生能力（下载源、镜像、账号体系、Java 管理），稳。

它继承 HMCL 的全部功能：模组加载器（Forge / NeoForge / Fabric / Quilt / OptiFine）、整合包、Java 管理、账号登录、联机、全平台多架构。

## 上手

1. **下载**　到 [Releases](https://github.com/LeeWikie/HMCL-AE/releases/latest) 拿对应平台的文件。
2. **运行**　Windows 双击 `.exe`；Linux / macOS 用 `java -jar HMCL-AE-xxx.jar`（需 Java 8+）。
3. **配模型**　侧栏「AI 助手 → AI 设置 → 模型服务」添加：填端点、API Key、模型名。
4. **开聊**　直接说你想干什么。中文体验更好。

> 启动器免费开源，但 **AI 功能需要你自备模型 API Key**（没有免费内置模型）。
> 支持 **OpenAI**、**Anthropic** 两种协议，以及任何 **OpenAI 兼容端点**（如 DeepSeek、智谱、Moonshot 等）。

## 目前的不足

这是 beta，还在快速迭代：

- 安装 / 下载暂时是阻塞式的，下载时聊天会等待。
- 模型差距明显：能力强的模型（Claude、GPT、DeepSeek-R1 等）执行复杂流程更稳。
- 部分能力仍在做：后台任务进度、整合包导出、数据包管理等。
- 彩色 emoji 需在聊天设置里手动开启并联网下载，默认走系统渲染。

## 从源码构建

```bash
git clone https://github.com/LeeWikie/HMCL-AE.git
cd HMCL-AE
./gradlew :HMCL:run                # 运行
./gradlew :HMCL:makeExecutables    # 产出 .exe / .jar / .sh 到 HMCL/build/libs/
```

模块：**HMCLAI**（AI 核心：agent 循环、工具、搜索、技能）· **HMCL**（启动器 + AI 界面 + Minecraft 操作工具）· **HMCLCore**（游戏仓库 / 下载 / 崩溃分析）· **HMCLBoot**（引导）。

## 许可

**GPLv3**（含附加条款：分发修改版须改名以区别原版、保留版权声明）。

HMCL-AE 是 [HMCL](https://github.com/HMCL-dev/HMCL) 的修改版，保留其原始版权声明。感谢 HMCL 及其自 2015 年以来的全体贡献者。

<details>
<summary>其它语言文档</summary>

[English](docs/README.md) · [简体中文](docs/README_zh.md) · [繁體中文](docs/README_zh_Hant.md) · [日本語](docs/README_ja.md) · [español](docs/README_es.md) · [русский](docs/README_ru.md) · [українська](docs/README_uk.md)

</details>
