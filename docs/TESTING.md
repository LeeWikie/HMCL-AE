# HMCL-AE 测试流程

HMCL-AE 的 AI agent 既有**确定性逻辑**(工具实现、策略、解析、备份等),也有**行为/集成层**(模型到底会不会、用没用对工具)。两者的失败模式不同,测试分两层,都纳入标准流程。

## 分层策略

| 层 | 工具 | 抓什么 | 速度/成本 |
|---|---|---|---|
| **单元测试** | JUnit(`:HMCLAI:test` / `:HMCL:test`) | 确定性逻辑:AiExecutionPolicy 决策矩阵、RememberStore、AiSettings 持久化、AiDataBackup、DangerousCommands、adapter 截断/防空转 | 秒级、免费、确定 |
| **CLI 集成冒烟** | 无界面 CLI(`runAiCli`)+ 低智商基线模型 mercury-2 | 行为:模型选对工具没?有没有退回 shell?工具对真实 HMCL 状态能跑通? | 慢、需 key/联网、非确定 |

> 单元测试**抓不到**「agent 误用工具 / 退回 shell / 网络挂」这类本项目真实痛点——那是 CLI 集成层的职责。
> CLI 用 **mercury-2 当最低智商基线**:蠢模型都能做对,说明提示词/工具够稳;它做错就是工程问题。

## 标准测试流程(每次改动后)

```bash
cd /c/Users/Administrator/Desktop/HMCL
export HTTPS_PROXY=http://127.0.0.1:7890 HTTP_PROXY=http://127.0.0.1:7890   # 国内代理

# 1) 编译
./gradlew :HMCLAI:compileJava :HMCL:compileJava -q

# 2) 单元测试(确定性门禁)
./gradlew :HMCLAI:test :HMCL:test -q

# 3) CLI 集成冒烟(行为门禁,无界面、参数驱动、详细日志)
#    一键跑整套真实小白口吻场景(覆盖各工具,断言调对工具+没退回shell+不卡):
bash scripts/ai-cli-smoke.sh                    # 用 mercury-2 基线
bash scripts/ai-cli-smoke.sh --fallback         # 切 deepseek-v4-flash
bash scripts/ai-cli-smoke.sh --only 实例列表     # 只跑某个场景

#    或手动单跑:
./gradlew :HMCL:runAiCli --args="--prompt '我都装了些啥版本啊' --timeout 90"
```

`scripts/ai-cli-smoke.sh` 是标准行为冒烟套件:用**真实、模糊、小白口吻**的中文 prompt(不是干净的技术描述),每条断言 ① 调用了预期的原生工具 ② **没有退回 shell** ③ 有 `[RESULT]`(没卡)。每条带硬 `--timeout`,整套**永不挂起**。新增工具时往 `SCENARIOS` 数组里加一条对应的小白场景。

### 多轮 / ask 的测试
- 多轮:`--session <path>`。第 1 轮写 session.json,第 2 轮带同一 `--session` 续上(`--prompt` 即下一句)。
- ask:用 `--answer <值>`(可重复,按顺序;值=选项序号或自定义文本)预供答案。**供不上时 ask 不会瞎猜**,而是让模型停下来用文字说明它要什么并存进 session——再带一个 `--answer` 重跑即可继续(`[ASK-DEFERRED]` 会提示)。

## 测试模型配置

`.ai-cli-test.json`(仓库根,**gitignored,含密钥,绝不提交**):
- primary:Inception **mercury-2**(`https://api.inceptionlabs.ai/v1/chat/completions`,OpenAI 兼容,原生 tools,128K,极低价)——最低智商基线。
- fallback:DeepSeek **deepseek-v4-flash**(低配低价)。

## 无人值守约束(给自动化/vibe-coding 工具)

- 只**编译 + 模块测试 + CLI 冒烟**,不启动 GUI(`:HMCL:run`)。
- 测试需放开沙箱 + 走本地代理 7890。
- CLI 是无界面的,正是为了让没法操作 UI 的工具能端到端测到 ai-feature 的全部能力。

> 注:`runAiCli` 的精确参数以 `--help` 为准(随 CLI 实现可能微调)。
