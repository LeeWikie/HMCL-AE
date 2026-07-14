<div align="center">
  <img src="HMCL/src/main/resources/assets/img/icon@8x.png" alt="HMCL-AE" width="80"/>

  # HMCL-AE

  **HMCL 的分支,内置一个通过工具调用执行操作的 Minecraft 助手。**

  [下载](https://github.com/LeeWikie/HMCL-AE/releases/latest) · [更新日志](CHANGELOG.md) · [反馈](https://github.com/LeeWikie/HMCL-AE/issues) · [上游 HMCL](https://github.com/HMCL-dev/HMCL)

  <sub>GPLv3 · Java 17+ · Windows / Linux / macOS / FreeBSD · v0.1.1-alpha</sub>
</div>

---

## 简介

HMCL-AE 在 [HMCL](https://github.com/HMCL-dev/HMCL) 基础上内置一个 AI 助手。该助手不是纯对话界面，通过工具调用直接执行启动器操作：安装版本与加载器、管理模组与整合包、诊断崩溃、管理实例 / 世界 / 账号等。所有操作复用 HMCL 本体能力(下载源、镜像、账号体系、Java 管理)。

HMCL-AE 继承 HMCL 的全部功能:模组加载器(Forge / NeoForge / Fabric / Quilt / OptiFine)、整合包、Java 管理、账号登录、联机、全平台多架构。

## AI 助手

- **工具集**:数十个工具,覆盖文件、模组、加载器、版本、实例、世界、存档、账号、Java、系统信息、联网搜索、NBT、OCR、MCP。
- **执行模型**:调用工具 → 读取结果 → 决定下一步 → 继续调用,循环至任务完成;工具失败结果回传模型,据此定位问题、调整重试。
- **护栏与审批**:统一护栏架构(拒绝态短路、结构化阻断原因、失败信封);审批模式 Manual / Auto / yolo,叠加 Plan 模式;无人值守下危险操作硬阻断。
- **模型库**:内置约 1600 个模型的元数据(上下文窗口、输出上限、模态、定价),按模型 ID 自动匹配。
- **诊断 Trace**:可开启逐轮记录(request / response / tool 调用)的 JSONL,用于复盘与反馈。

## 安装

1. 到 [Releases](https://github.com/LeeWikie/HMCL-AE/releases/latest) 下载对应平台文件。
2. 运行:Windows 双击 `.exe`;其它平台执行 `java -jar HMCL-<版本>.jar`(需 Java 17+)。
3. 配置模型:侧栏「AI 助手 → AI 设置 → 模型服务」填入端点、API Key、模型名。

**AI 功能需自备模型 API Key**,无内置免费模型。支持 OpenAI、Anthropic 两种协议,以及任意 OpenAI 兼容端点(DeepSeek、智谱、Moonshot 等)。

## 定位与状态

- 处于 **alpha 阶段**:底层 Agent 逻辑仍在重写,允许不兼容变更;版本序列自 0.1.0-alpha 重置。
- 面向能自备模型 API Key 的用户;不内置模型,不做商业化。
- AI 可执行下载、安装、修改与删除文件、编辑与删除存档等**破坏性操作**。请对重要数据(尤其存档)先行备份,并在确认弹窗中核对操作后再放行。

## 已知限制

- 安装 / 下载为阻塞式,下载期间聊天等待。
- 模型能力差距明显:能力较强的模型执行复杂流程更稳定。
- 部分能力仍在完善(后台任务进度、整合包导出、数据包管理等)。
- 彩色 emoji 需在聊天设置中手动开启并联网下载,默认使用系统渲染。

## 从源码构建

```bash
git clone https://github.com/LeeWikie/HMCL-AE.git
cd HMCL-AE
./gradlew :HMCL:run                # 运行
./gradlew :HMCL:makeExecutables    # 产出 .exe / .jar / .sh 到 HMCL/build/libs/
```

模块划分:**HMCLAI**(AI 核心:agent 循环、工具、搜索、技能)· **HMCL**(启动器、AI 界面、Minecraft 操作工具)· **HMCLCore**(游戏仓库 / 下载 / 崩溃分析)· **HMCLBoot**(引导)。

## 许可

**GPLv3**(含附加条款:分发修改版须改名以区别原版、保留版权声明)。

HMCL-AE 是 [HMCL](https://github.com/HMCL-dev/HMCL) 的修改版,保留其原始版权声明。感谢 HMCL 及其自 2015 年以来的全体贡献者。

[English](docs/README.md)

> 其它语言版本(简体中文 / 繁體中文 / 日本語 / español / русский / українська)暂为未同步的上游 HMCL 原文,尚未跟进 AE 的 AI 功能,已移除对应链接以免误导;欢迎协助翻译。
