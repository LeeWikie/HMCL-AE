# AI 能做什么

AI agent 有一套**真正的工具**——它调用工具、拿到结果、决定下一步,反复循环直到完成任务。下面是它能做的所有事情。

## 文件与命令

| 工具 | 做什么 |
|---|---|
| `read` | 读取文件内容,或列出目录 |
| `write` | 创建新文件或完全覆盖已有文件 |
| `edit` | 精确替换文件中的一段文字 |
| `grep` | 用正则表达式搜索文件内容 |
| `glob` | 用通配符匹配文件名 |
| `shell` | 在当前系统 Shell(PowerShell/bash)执行命令 |

> `shell` 会执行危险命令(如 `rm -rf`、`format`)时触发**二次确认弹窗**。

## 网络

| 工具 | 做什么 |
|---|---|
| `web_search` | 联网搜索(需要配 Tavily/SearXNG Key) |
| `web_fetch` | 抓取一个具体 URL 的内容 |

> 搜索优先用 `web_search`,`web_fetch` 只用来抓已知 URL(如搜索结果里的链接)。

## Minecraft 操作

| 工具 | 做什么 |
|---|---|
| `list_instances` | 列出当前已安装的所有实例 |
| `list_game_versions` | 拉取**真实的** Minecraft 版本列表(不靠模型记忆) |
| `search_mods` | 在 Modrinth(或 CurseForge)搜索 mod |
| `install_mod` | 下载并安装 mod 到指定实例的 mods 目录 |
| `install_loader` | 安装 Minecraft 版本 + 模组加载器(Fabric/Forge/NeoForge 等) |
| `launch_instance` | 启动一个实例 |
| `edit_instance` | 编辑实例(改名;游戏设置走 config-hmcl skill) |
| `delete_instance` | **删除实例**(危险操作,需要确认) |
| `search_resourcepacks` | 搜索资源包 |
| `install_resourcepack` | 安装资源包 |
| `search_shaders` | 搜索光影 |
| `install_shader` | 安装光影 |
| `search_modpacks` | 搜索整合包 |
| `install_modpack` | 安装整合包(会创建新实例) |
| `search_worlds` | 搜索世界存档(仅 CurseForge) |
| `match_known_errors` | 用 HMCL 原生规则库匹配崩溃原因 |
| `sleep` | 等待指定秒数(用于等待长任务) |

## 向用户提问

| 工具 | 做什么 |
|---|---|
| `ask` | 当 AI 需要你的决策时(装哪个版本?哪个加载器?选哪些 mod?),会在输入框上方弹出一个**分步向导**,一次一个问题,可上一步/下一步,每题自动带"自定义"选项 |

## 自我配置

AI 可以修改自身的配置和 HMCL 原生配置——通过内置的技能(skill):

- `config-hmcl-ae` — 修改 AE 自己的配置(provider、API key、skill、MCP 等)
- `config-hmcl` — 修改 HMCL 原生配置(启动器设置、游戏设置、实例设置)

详见 [[技能 Skills]]。

## 实际效果举例

**「帮我装个最新版 + Fabric,再装 Sodium 及能用的附属」**
AI 会:列实例 → 查真实版本列表 → 搜 Sodium 及附属 → **问**你装哪个版本/加载器/哪些附属(分步提问) → 装版本+加载器 → 依次装你选的 mod → 报告完成。

**「我的 Minecraft 崩溃了」**
AI 会:读 `logs/latest.log` → glog 找最新的崩溃报告 → 读崩溃内容 → 用 `match_known_errors` 匹配已知故障 → 用中文解释原因 + 修复方法。
