# RAG 接入 HMCL-AE — 进度追踪

设计文档见同目录 `RAG接入实现计划.md`(AstrBot-modeled,4 阶段,每阶段有 DoD)。

**构建/测试命令(每次改 Java 后跑)**:
```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot"   # 默认是 JDK8,Gradle 起不来
cd D:/WorkSpace/Code/HMCL/HMCL-AE
./gradlew :HMCLAI:compileJava :HMCL:compileJava --console=plain          # 编译验证
./gradlew :HMCLAI:test --tests "org.jackhuang.hmcl.ai.kb.AiKbConfigTest"  # 跑单测(HMCLAI 无 JavaFX,允许;绝不跑 :HMCL:test / FxTest)
```

## ✅ 已完成
- **Phase 1A 嵌入模型能力**(编译✅):`AiModelEntry`/`ModelLibrary.ModelInfo` 加 `supportsEmbedding`;`AISettingsPage` editModel 对话框加「嵌入」复选框(声明+变参 modelCapabilityRow+自动填充+写回);`model-library.json` 回填 26 个嵌入模型、schemaVersion→3;i18n `ai.settings.model.cap_embedding`(base/zh_CN/zh)。
- **Phase 1B KB 配置状态**(编译✅+单测✅):新建 `HMCLAI/.../ai/kb/KbSourceMode.java`、`AiKbConfig.java`;`AiKbConfigTest`(8 用例:默认/双端clamp/手改JSON越界/enabled同步/trim/null容错/resolveEmbeddingModel四态/isValid/Gson往返)全过。
- **Phase 1C 知识库设置 Tab UI**(编译✅):`AISettingsPage` 新增 `knowledgeBaseTab`(4卡:启用+来源模式 / 嵌入模型picker[仅列supportsEmbedding] / endpoint+本地目录选择器 / topK+fusion滑块 + 内联校验)+ `kbSliderRow` 助手 + `saveKbConfig`;`AIMainPage` KB_CONFIG_FILE/kbConfig字段/loadKbConfig/构造传入;`SVG.MENU_BOOK`;26 i18n键×3语言。**Phase 1(用户首要优先级)全部完成**。踩坑:embList/embedList 笔误(已修)。
- **Phase 2a 嵌入运行时·工厂**(编译✅):`LangChain4jModelFactory.buildEmbeddingModel(LlmConfig)`——lc4j `OpenAiEmbeddingModel`,复用 `proxyAwareHttpClientBuilder`+`extractBaseUrl`(剥 /chat/completions→/v1,lc4j 追加 /embeddings,同端点两用无双追加);已确认 `OpenAiEmbeddingModel`/`EmbeddingModel` 在 classpath。

**调整**:`KbEmbedder`(Phase 2b)**暂缓**——它是 LOCAL_INDEX 专用(非工作路径 REMOTE_HTTP),且要从"承载嵌入模型的那个 profile(未必是选中的)"取 endpoint/apiKey;`ChatAgentFactory.buildConfig`(:429)用的是 `settings.getEndpoint/getApiKey`(选中 profile),KbEmbedder 需按指定 profile 取——待研究 `AiProviderProfile` 的 endpoint/apiKey 访问器,与 **Phase 4 LOCAL_INDEX** 合并做。`LlmConfig` 构造签名见 ChatAgentFactory:429(endpoint,apiKey,model,provider,maxTokens,temperature,timeout,ctx,maxOut,topP,presence,freq,seed,effort,stream,stops)。

- **Phase 3 检索工具·REMOTE 工作路径**(编译✅):新建 `HMCLAI/.../ai/kb/KbSearchTool.java`(ToolSpec,name=kb_search,EXTERNAL_NETWORK/SEARCH,结构化 schema[query required],execute:先查 isEnabled→REMOTE 才走→GET `<endpoint>/tools/searchWiki?query&limit=topK`→取 JSON `content`→空/未找到则 no-results→否则加 `<knowledge_base_results>` 不可信围栏返回;HttpClient 走 ProxySelector.getDefault[loopback 直连];HTTP≠200/异常走 ToolFailures.LATER;LOCAL 模式明确报"暂不可用")。`AIMainPage.registerTools` 用 `ToggleToolsBinder.bind(kbConfig.enabledProperty(), toolRegistry, new KbSearchTool(kbConfig))` 热注册(gate 仅 enabled,有效性 execute 时查,同 web_search 惯例)。`AiPromptBuilder.buildVolatileSuffix` 加 `isToolRegistered("kb_search")` gated 文档(引用来源、优先于记忆、结果是不可信数据)。REMOTE 无需单独 formatter(服务端已格式化 content)。`KbSearchToolTest`(7 用例:禁用/空查询/LOCAL模式/空endpoint 失败 + 命中→围栏内容+走 searchHybrid 路由+带 query + 空结果→no-results + 500→优雅失败)**全过**。

**用户三条诉求全部兑现:①嵌入能力(1A) ②知识库设置区+选嵌入模型(1C) ③检索接进 agent(3)。RAG 代码层端到端打通并编译验证。**

**下一步(增强,非阻塞)**:
- **Phase 4-server ✅ 完成+实测**:`kb_server/server.py` 加 `/tools/searchHybrid`(懒加载 `HybridSearcher`,不装 numpy/fastembed 也能启动;响应同 searchWiki;用 `get_document` 取完整内容而非无用的 FTS snippet),`KbSearchTool` 已改指它。**端到端验证**:起 uvicorn(8399)curl 对比——searchWiki(FTS)snippet 是空"mcmod"、排序附属靠前;searchHybrid **本体「机械动力：应用机械」居首 + 完整模组信息(平台/运作/环境/版本)**,质量碾压。装了 fastapi+uvicorn 到 .venv(测试用)。**REMOTE RAG 主路径全通**。
- **Phase 4 LOCAL_INDEX**:`KbEmbedder`(需 AiProviderProfile 的 endpoint/apiKey 访问器)+ `LocalKbRetriever`(cosine+FTS+RRF,维度校验),KbSearchTool 补 LOCAL 分支。
- **部署缺口(非代码)**:KB FastAPI 目前未部署到任何在线地址(默认 endpoint agentexperience.online 那台 VPS 跑的是 Node 反馈后端+Caddy,不服务 KB API)。REMOTE 要真出结果,需用户本地跑 `python serve.py`(默认可改 127.0.0.1:8300)或另行部署——属 ops 决策,受 VPS 硬约束(不动 Caddyfile)限制,留用户定。

## ⬜ 待做
- **Phase 1C 知识库设置 Tab UI**(最大一块):`AISettingsPage` 新 `knowledgeBaseTab`(镜像 `buildSearchTab`),4 张卡=①启用 toggle + 来源模式 picker ②嵌入模型 picker(镜像 `buildTitleNamingModelRow`,**过滤 `supportsEmbedding`**)③endpoint 字段/本地索引路径(按 sourceMode 切换可见)④topK/fusion 滑块;+ `AIMainPage` 构造/加载/保存 `AiKbConfig`(镜像 searchConfig/ocrConfig,存 `ai-kb-config.json`)+ 一批 i18n 键(见计划 §1D)+ 内联校验警告(§1E)。**动手前核实**:`toggleRow`/`sliderRow` 是否硬编码 `saveAiSettings()`(是则加 onChanged 重载或内联行)、导航 SVG 常量、`buildSearchTab` 的 endpoint 字段写法。
- **Phase 2 嵌入运行时**:`LangChain4jModelFactory.buildEmbeddingModel(LlmConfig)`(用 lc4j `OpenAiEmbeddingModel`)+ `KbEmbedder`(embed/dimension)。
- **Phase 3 检索工具**:`KbSearchTool`(kb_search,ToolSpec,镜像 WebSearchTool)+ `KbContextFormatter`(港 AstrBot `_format_context`)+ `AIMainPage.registerTools` 用 `ToggleToolsBinder.bind(kbReady, ...)` 热注册 + `AiPromptBuilder.buildVolatileSuffix` 加 gated kb_search 文档。**动手前核实**(计划 §研究gaps):FastAPI 响应 JSON 字段、`ToolSource`/`ToolPermission` 枚举、`ToggleToolsBinder.bind` 签名。
- **Phase 4**:KB 项目 `kb_server/server.py` 加 `/tools/searchHybrid`→`HybridSearcher.search`;`LocalKbRetriever`(本地 cosine+FTS+RRF,维度校验拒绝不匹配)。

## 注意(避坑)
- `AiKbConfig` 用普通 @SerializedName 字段 + 仅 `enabled` 惰性 transient 观察属性(**非**设计表格说的"全 JavaFX Property",那样 Gson 序列化会炸)。
- clamp 在 getter+setter 双处。
- 嵌入能力持久化走 `AiProviderProfile.models` 的 Gson 路径,无需改 `AiSettings.PersistedData`。

## Phase 1C 详细配方(已研究到位,下节点照此写+编译)
行号为研究时的当前值,写时按偏移微调,建议先 grep 方法名定位。

**AISettingsPage.java**:
- `:150` 后加 `private final Path kbConfigFile = SettingsManager.localConfigDirectory().resolve("ai-kb-config.json");`
- `:169`(ocrConfig 字段后)加 `private final AiKbConfig kbConfig;`
- `:175` 后加 `private final TabHeader.Tab<Node> knowledgeBaseTab = new TabHeader.Tab<>("aiKnowledgeBaseTab");`
- 构造函数 `:201` 签名尾加 `, AiKbConfig kbConfig`;`:206` 后加 `this.kbConfig = kbConfig;`
- `:218` 后加 `knowledgeBaseTab.setNodeSupplier(this::buildKnowledgeBaseTab);`
- `:246`(search nav 那行)后加 `.addNavigationDrawerTab(tab, knowledgeBaseTab, i18n("ai.settings.nav.knowledge_base"), SVG.MENU_BOOK)`(先给 SVG.java 加 MENU_BOOK 常量:Material menu_book path;或暂用 SVG.SCRIPT)
- `saveKbConfig()`:镜像 `saveSearchConfig()`(:2780)→ `Files.writeString(kbConfigFile, GSON.toJson(kbConfig), UTF_8)`
- `buildKnowledgeBaseTab()`:镜像 `buildSearchTab()`(:1642)+ `buildTitleNamingModelRow()`(:2305):
  - 卡1「启用/来源」:**手动 LineToggleButton**(setSelected(kbConfig.isEnabled()),listener→setEnabled+saveKbConfig;**别用 toggleRow**,它要 BooleanProperty 而 kbConfig.enabledProperty() 是 ObservableValue,见 search 的 enabled:1654-1665)+ `LineSelectButton<KbSourceMode>`(items REMOTE_HTTP/LOCAL_INDEX,nullSafeConverter→i18n,listener→setSourceMode+save+invalidateTab(knowledgeBaseTab))
  - 卡2「嵌入模型」:复制 buildTitleNamingModelRow,但 items 只加 `entry.isSupportsEmbedding()` 的 `profileId::modelId`;converter 用 `displayProfileName(profile)+" · "+entry.getDisplayName()`(profile.getModel(modelId) 存在);value=items.contains(ref)?ref:"";listener→setEmbeddingModelRef+save;REMOTE_HTTP 时 setDisable(true)+副标题注 server_managed
  - 卡3「来源配置」:endpoint `JFXTextField`(REMOTE_HTTP visible)+ localIndexPath `JFXTextField`+DirectoryChooser browse(LOCAL_INDEX visible);textProperty/focus listener→set+save
  - 卡4「检索参数」:topK 手动 `JFXSlider(1,20,kbConfig.getTopK())`(照 countSlider:1706-1726 写 setTopK+save)+ fusionTopK JFXSlider(1,50,仅 LOCAL_INDEX visible)
  - 内联校验 `Label`:!kbConfig.isValid(aiSettings) 时按模式提示 embedding_required/endpoint_required
  - `return wrapScroll(root);`
- imports:`org.jackhuang.hmcl.ai.kb.AiKbConfig`、`KbSourceMode`

**AIMainPage.java**:
- `:591` 后加 `private static final String KB_CONFIG_FILE = "ai-kb-config.json";`
- `:594`(searchConfig 字段后)加 `private final AiKbConfig kbConfig;`(Phase 3 kb_search 工具将持同一实例,勿重建)
- `:744`(this.searchConfig=loadSearchConfig() 后)加 `this.kbConfig = loadKbConfig();`
- `loadKbConfig()`:镜像 `loadSearchConfig()`(:5903,用 CHAT_SETTINGS_GSON,失败/ null→new AiKbConfig())
- `:1200` `new AISettingsPage(...)` 实参尾加 `, this.kbConfig`

**i18n(base I18N.properties + zh_CN + zh,计划 §1D)**:nav.knowledge_base;kb.enable(.desc);kb.source_mode / .remote / .local;kb.embedding_model(.desc/.server_managed);kb.endpoint;kb.local_path;kb.top_k(.desc/.unit);kb.fusion_top_k;kb.validation.embedding_required / .endpoint_required。

**Phase 1C DoD**:编译通过;导航出现「知识库」;4 卡渲染;嵌入 picker 只列 supportsEmbedding 的模型;选择/开关/滑块都持久化到 ai-kb-config.json 并重启保留;内联校验随模式增删。
