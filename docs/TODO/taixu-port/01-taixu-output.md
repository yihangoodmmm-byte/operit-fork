# 01 · 太墟 AI 输出链路剖析

> 只读分析对象：/storage/emulated/0/inmyfeelings/inmyfeelings/taixu-main
> 分析范围：harness 的模型调用/流式/落库层 + feature/chat 的消费层。
> 本文件只记录事实与结论，不包含实施动作。
>
> 引用格式：仓库相对路径:行号（行号以当前 taixu-main 快照为准）。

---

## 0. 结论速览

1. **输出链路是"字符串增量 + 结构化旁路"**：正文/思考走 `onDelta(String)` / `onReasoning(String)` 回调，工具调用增量走 `ToolCallStreamProgress` 结构化回调；两者最终收敛为一个 `ChatResult`（content/toolCalls/reasoningContent/usage）。
   证据：`harness/src/main/java/top/wkbin/taixu/harness/ProviderClient.kt:81-95`、`.../HarnessProviderRunner.kt:128-163`、`.../ChatResult.kt:3-31`。
2. **UI 拿到的不是 diff，而是"同一 id 的全量快照替换"**：`StreamBuffer` 每 16ms 产出一个 `ChunkedText` 不可变快照，`SessionMessageProjector.streamText()` 用**同一个 assistantId** 原地替换列表元素；LazyColumn 的 key 就是 `message.id`。
   证据：`.../StreamBuffer.kt:38-50`、`.../ProviderClient.kt:685`、`.../projection/SessionMessageProjector.kt:221-246`、`feature/chat/.../RoundCollapse.kt:18-30`、`feature/chat/.../ChatMessageList.kt:181`。
3. **中断语义是"已产出内容优先"**：用户取消 / 本地流处理异常 / 重试耗尽都会把已累积文本落库（取消路径在 `NonCancellable` 中落库）；但**网络重发前会主动清空本轮流式气泡**，宁可重生成也不拼接半截内容。
   证据：`.../HarnessProviderRunner.kt:65-83`、`:173-204`、`:524-552`、`:430-431`。
4. **"续写/恢复"不是一条机制，而是四层**：Anthropic `pause_turn` 协议内续流、输出上限单次扩容重放、上下文溢出紧急压缩、进程重启后的 durable task + operation + 悬空工具调用修复。
   证据：`.../AnthropicApi.kt:68-114`、`.../ProviderOutputRecovery.kt:7-33`、`.../ProviderContextRecovery.kt:9-38`、`.../HarnessLoop.kt:388-494`。
5. **与 Operit 的关键差异**：太墟把"消息（含 partial）"当作唯一事实源并持久化（Room entry + operation 同事务），Operit 的流是 `Stream<String>` 冷流，partial 主要驻留内存、靠取消时快照 `detachStreamingAiMessage` 抢救。前者天然支持"杀进程后接着干"，后者更依赖进程存活。
   证据：`.../session/SessionTreeStore.kt:114-129`、`.../operation/OperationCoordinator.kt:254-277`、`_operit-src/.../api/chat/llmprovider/AIService.kt:60-74`、`_operit-src/.../services/core/MessageProcessingDelegate.kt:496-573`。

---

## 1. 一次模型调用的完整数据流

### 1.1 总图

```
[网络]  HTTP/SSE（OpenAI 兼容 / Anthropic）
   │  OkHttp response.body.source().readUtf8Line() : String
   ▼
[协议层]  ChatApi.executeStream / AnthropicApi.streamOnce
   │  逐行 "data: {json}" → JsonObject
   │
   ├─ delta.content / content_block_delta(text_delta)
   │        → ThinkTagStreamDemuxer.onContentChunk(String) → onDelta(String)
   ├─ delta.reasoning_content|reasoning / thinking_delta
   │        → onReasoning(String)
   └─ delta.tool_calls[] / input_json_delta
            → ToolCallAccumulator.publishProgress(...) → onToolProgress(ToolCallStreamProgress)
   │
   ▼
[回合调用层]  HarnessProviderRunner.callProviderWithRetry
   │  onDelta(chunk)  → StreamBuffer.append(chunk)          （ChunkedText.Builder）
   │                    StreamBuffer.publishSnapshotIfDue(now, 16ms): ChunkedText?
   │                    SessionMessageProjector.streamText(sessId, assistantId, assistantAt, ChunkedText)
   │  onReasoning     → StreamBuffer(maxChars=128K) → streamReasoning(...)
   │  onToolProgress  → 仅更新状态条（不写消息列表）
   │  流结束          → ChatResult(content, toolCalls, reasoningContent, usage, reasoningMs)
   │
   ▼
[回合解释层]  TurnRunner.run → AgentTurnRunner（harness:core）
   │  ProviderResponseNormalizer.normalize(ChatResult, rawText, toolsEnabled)
   │     → NormalizedProviderResponse(result, rawText, displayText, toolCalls, …)
   │  → TurnOutcome.{Complete, Continue, RoundLimit, Failed}
   │
   ▼
[持久化层]  HarnessProviderRunner.persistAssistantOutput → persistAssistant
   │  AssistantText(id=assistantId, textContent=ChunkedText, reasoning, usage, …)
   │  → OperationCoordinator.providerSettled(...)  （entry + usage + operation + lane leaf 同事务）
   │  → SessionMessageProjector.publishPersisted(...)
   │
   ▼
[投影层]  SessionMessageProjector
   │  MutableStateFlow<List<HarnessMessage>>（按 id 原地替换 / 追加）
   │  mirrorIfForeground → _foregroundMessages
   ▼
[引擎出口]  HarnessLoop.messages : StateFlow<List<HarnessMessage>>
   ▼
[VM]  ChatViewModel.messages = harnessLoop.messages
   ▼
[Compose]  ChatScreen（collectAsStateWithLifecycle）
   ▼
[列表]  ChatMessageList → projectChatMessages() → LazyColumn(key = ChatRenderItem.stableKey = message.id)
   ▼
[气泡]  AssistantBubble(AssistantText) → live ? 纯 Text : MarkdownText
```

### 1.2 逐层类型表

| 层 | 输入类型 | 输出类型 | 位置 |
|---|---|---|---|
| SSE 读行 | `ResponseBody.Source` | `String`（每行） | `ProviderClient.kt:148-171`；`AnthropicApi.kt:172-243` |
| 增量分流 | `String` chunk | `onDelta(String)` / `onReasoning(String)` / `onToolProgress(ToolCallStreamProgress)` | `ProviderClient.kt:192-243`；`ToolCallStreaming.kt:48-54` |
| 思考/正文拆分 | `String` | `onReasoning` / `onDelta`（处理 `<think>` 跨片） | `ProviderClient.kt:1140-1261` |
| 累积 + 节流 | `String` | `ChunkedText` 快照（`isExtensionOf` 可判定同 lineage） | `StreamBuffer.kt:14-50`；`ChunkedText.kt:60` |
| 回合结果 | 累积缓冲 + usage | `ChatResult` | `ChatResult.kt:3-17`；`ProviderClient.kt:257-271` |
| 驱动结果 | `ChatResult` | `TurnProviderOutcome.Success(result, streamText)` / `Failed(message)` | `TurnRunner.kt:8-11`；`HarnessProviderRunner.kt:430-431` |
| 协议归一 | `ChatResult` + rawText | `NormalizedProviderResponse`（含 `displayText`） | `ProviderResponseNormalizer.kt:10-59` |
| 领域消息 | `NormalizedProviderResponse` | `AssistantText(textContent: ChunkedText)` | `HarnessMessage.kt:122-148` |
| 投影 | `HarnessMessage` | `List<HarnessMessage>`（StateFlow 值） | `SessionMessageProjector.kt:179-198` |
| UI | `List<HarnessMessage>` | `ChatRenderItem` → Compose 节点 | `RoundCollapse.kt:72-90`；`ChatMessageList.kt:181-256` |

### 1.3 关键代码路径

- 每轮先分配 **assistantId / assistantAt**，再进入 `turnRunner.run`：
  `HarnessLoop.kt:1238-1259`。
- 流式回调挂在 `providerClient.chatStream(...)` 的命名参数上（onReasoning / onToolProgress / 尾随 lambda = onDelta）：
  `HarnessProviderRunner.kt:127-164`。
- 流结束做一次"强制补发尾块"，避免 16ms 节流把最后几字符吃掉：
  `HarnessProviderRunner.kt:165-172`。
- 成功后 `messageProjector.endStreaming(sessId)` 并返回 `Success(streamed, streamText.toString())`：
  `HarnessProviderRunner.kt:430-431`。

---

## 2. 流式增量怎么传递

### 2.1 是字符串流，但不是"裸字符串流"

两条通道并存：

1. **正文与思考：字符串增量**（`onDelta: (String) -> Unit`、`onReasoning: (String) -> Unit`），SSE 收到什么就回调什么，不做协议级事件封装。
   证据：`ProviderClient.kt:81-87`；`AnthropicApi.kt:216-227`。
2. **工具调用：结构化事件**（`ToolCallStreamProgress(name, addedLines, deletedLines, argumentChars)`），只用于 UI 进度，不参与最终执行。
   证据：`ToolCallStreaming.kt:16-54`；`HarnessProviderRunner.kt:146-152`。

最终才收敛成结构化结果：`ChatResult` 持有 `content/toolCalls/reasoningContent/usage/reasoningMs/responsesTurn`（`ChatResult.kt:3-31`）。也就是说：**流是字符串，回合是结构**。

补充事实：
- 工具参数按 `index` 累积成 `ToolCallAccumulator(arguments: StringBuilder)`，网关不给 index 时按 `id` 变化切槽，避免并行调用互相污染：`ProviderClient.kt:206-243`。
- 思考内容有硬上限 128K 字符（正文无上限，因为工具协议依赖完整原文）：`ProviderClient.kt:675`、`StreamBuffer.kt:11-13`。
- 思维链重复自旋会被检测并"静音"后续 reasoning：`ProviderClient.kt:1240-1261`。

### 2.2 增量如何绑定到 UI 状态

绑定键只有一个：**assistantId（每轮开始生成的 UUID）**。

1. 每轮生成 id：`HarnessLoop.kt:1238`。
2. 累积后按 16ms 节流发布**全量快照**（不是增量补丁）：
   `StreamBuffer.kt:38-50`，节流常量 `ProviderClient.STREAM_PUBLISH_INTERVAL_MS = 16L`（注释明确：只节流快照分配，不节流 SSE 接收）`ProviderClient.kt:685`。
3. `SessionMessageProjector.streamText` 在 `List<HarnessMessage>` 中**按 id 找到旧元素并原地替换**；找不到才追加：
   `SessionMessageProjector.kt:221-246`。
4. `streamReasoning` 用同一 id 生成"只有思考、正文为空"的占位气泡，后续正文到达时同 id 覆盖：
   `SessionMessageProjector.kt:249-266`。
5. 前台镜像 → `HarnessLoop.messages` → `ChatViewModel.messages` → Compose：
   `SessionMessageProjector.kt:63-71`、`HarnessLoop.kt:139-143`、`ChatViewModel.kt:233`、`ChatScreen.kt:122`。
6. 终态落库时仍然是**同一个 id**：`persistAssistant` 构造 `AssistantText(id = id, …)`（`HarnessProviderRunner.kt:491-504`），再用 `publishPersisted` 同 id 覆盖流式占位（`SessionMessageProjector.kt:184-198`）。

这套"同 id 快照替换"的直接效果：**UI 不需要理解 delta，也不需要维护增量状态机**；它只需要在每次 StateFlow 发射时拿到一条新的 `AssistantText`。

### 2.3 渲染性能的三处专门设计

- `ChunkedText` 结构性共享（2048 字符块 + 活动尾块），`snapshot()` 不复制全量字符串：`ChunkedText.kt:10-17, 78-110`。
- 增量 Markdown 解析只在"同一 lineage 的扩展"时续扫，否则 reset：
  `IncrementalMarkdown.kt:16-26`；`MarkdownText.kt:154`。
- 但**流式期间不走 Markdown**：`live == true` 时用纯 `Text`（截断 64K），流结束才切 `MarkdownText`：
  `ChatMessageBubbles.kt:443-497`、`ChatMessageBubbles.kt:575`。
  这避免了"每 16ms 重排一次富文本"的最大开销源，代价是流式期间没有最终排版。

---

## 3. 中断 / 失败怎么处理

### 3.1 处理矩阵

| 情形 | 是否保留已产出内容 | 是否重试/续写 | 代码位置 |
|---|---|---|---|
| 用户主动停止（协程取消） | **保留**，流式文本 + 思考落库；无内容则删除空气泡 | 不重试，向上抛 `CancellationException`，外层补一条"⏹ 已停止本次运行" | `HarnessProviderRunner.kt:173-178`、`:524-552`；`HarnessLoop.kt:1061-1091` |
| 取消表现为 IOException（socket 被主动关闭） | 同上，先按取消语义落库再传播 | 不重试（`ensureActive()` 先行） | `HarnessProviderRunner.kt:366-375` |
| 本地流式处理异常（UI 投影失败等） | **保留**；无内容则删气泡 | **严禁当网络错误重发**（避免重复文字/半截 JSON 污染上下文） | `HarnessProviderRunner.kt:179-204`；`StreamChunkHandling.kt:5-17` |
| 限流 429（非额度耗尽） | 重发前**丢弃**本轮缓冲与气泡 | 退避重试，预算 `maxNetworkRetries`；额度耗尽直接失败 | `HarnessProviderRunner.kt:205-231` |
| 输出达 token 上限（`length/max_tokens`） | 同上，丢弃后重生成 | 单次扩容输出预算后整轮重放（`OUTPUT_LIMIT_MAX_RECOVERIES = 1`） | `HarnessProviderRunner.kt:232-253, 571-573`；`ProviderOutputRecovery.kt:8-33` |
| Provider 拒绝输出预算 | 丢弃 | 预算减半严格降额重试一次 | `HarnessProviderRunner.kt:254-268` |
| 上下文溢出（413 / `context_length_exceeded`） | 先尝试自愈；不可恢复时**保留**半截内容 | 紧急压缩历史后重放；仍溢出则剥离图片再试；再失败才 `settleFailedStream` | `HarnessProviderRunner.kt:269-323`；`ProviderContextRecovery.kt:9-26` |
| 空响应（HTTP 200 但无内容） | 重发前丢弃 | 长上下文先压缩再试；否则最多重试 2 次；失败时保留原始响应首部作文案 | `HarnessProviderRunner.kt:324-365, 573`；`ProviderResponseNormalizer.kt:60-83` |
| 网络 IO / 5xx / TLS 中断 | 重发前丢弃；重试耗尽时 `settleFailedStream` **保留最后一版半截内容** | 瞬态故障至少保留 `TRANSIENT_MAX_RETRIES`；大上下文会压低预算但瞬态不受影响 | `HarnessProviderRunner.kt:366-401` |
| 首个事件超时（看门狗） | **删除**流式气泡，不落半截 | 不按网络错误重试，直接给可读说明 | `HarnessProviderRunner.kt:366-376, 557-569`；`FirstResponseWait.kt:25-39` |
| 模型不支持图片（400） | 丢弃后重发 | 剥离图片降级重试一次 | `HarnessProviderRunner.kt:402-426` |
| 进程被杀 / 重启 | 已落库内容都在 Room 里 | durable task + operation 恢复；悬空工具调用按重放策略修复 | `HarnessLoop.kt:388-494`、`:503-532`、`:716-745` |

**一个必须说清的反直觉点**：`resetStreamBaseline()` 在每次"网络/限流/输出上限/空响应"重发前都会 `streamText.clear()` 并 `messageProjector.remove(sessId, assistantId)`（`HarnessProviderRunner.kt:65-71`，调用点 `:226, 247, 268, 292, 309, 343, 364, 400, 420`）。太墟的取舍是：**同一次生成内不做"半截续拼"，只做"整轮重生成"**；真正跨"断点"的续写只在协议层（Anthropic `pause_turn`）与引擎层（工具轮次预算、进程恢复）发生。

### 3.2 续写

- **协议级续写**：Anthropic `pause_turn` 时把本轮 assistant 部分回复转成下轮请求前缀，逐段增量顺序上屏、usage 累加，最多 `MAX_PAUSE_TURN_CONTINUATIONS` 次：
  `AnthropicApi.kt:68-114`、`:116-135`、`:197-199`。
- **引擎级续写**：工具轮次预算用尽后不是硬停机，而是注入一条 steering 提示（要求先收束、必要时写 PROGRESS.md），再开新一段预算：
  `HarnessLoop.kt:1367-1374`、`:1412-1429`。
- **重启级续写**：`recoverAllInterruptedSessions` 按 durable task 的授权/尝试预算决定自动续跑或挂起；"结果未知"的任务宁可失败也不重放副作用：
  `HarnessLoop.kt:448-482`。

### 3.3 检查点与回退

- **每用户轮一个文件检查点**：`acceptUserInput` 里 `rewindController.beginTurn(...)`：
  `HarnessLoop.kt:1131`；实现 `RewindController.beginTurn` → `CheckpointStore.beginTurn`（`checkpoint/CheckpointStore.kt:74`，保留最近 `MAX_KEPT = 100` 轮，`:255`）。
- **回退 API 是 prepare/commit 两段式**，恢复文件前检测外部改动冲突，且支持 undo：
  `checkpoint/RewindController.kt:12-44`。
- **重生成 / 重试工具调用 / 编辑重发 / 分支切换**都走"截断消息树 + 重跑"：
  `HarnessLoop.kt:622-701`。
- **消息树本身可回退**：`messageStore.moveTo/rewindBefore` + `messageProjector.replaceAll`（`HarnessLoop.kt:626-635, 643-652, 688-699`）。

### 3.4 落库是"消息 + 操作状态"同事务

`persistAssistant` 在有 operationId 时走 `operationCoordinator.providerSettled`（`HarnessProviderRunner.kt:505-520`）；`settle()` 把 entry、usage、operation 快照、lane 叶子在同一次 `repository.settleEffect(...)` 中提交（`operation/OperationCoordinator.kt:254-277`）。这是"重启后能判断上次到底做完没"的数据基础。

---

## 4. UI 靠什么 key 保证不重建

1. **LazyColumn 项 key = `ChatRenderItem.stableKey` = `message.id`**：
   `ChatMessageList.kt:181`；`RoundCollapse.kt:18-30`。
   → 同一条消息在 16ms 一次的全量列表里始终是同一条 item，只做重组（recompose），不重建（dispose + recreate）。
2. **折叠按钮 key 是 `collapse_btn_<roundKey>`，`roundKey` = 该轮首条 `UserMessage.id`**：
   `RoundCollapse.kt:32-45`、`:194-199`。
3. **同步状态用 id 记住**：`ThinkingBlock` 的展开/翻译状态用 `rememberSaveable(id)`（`ChatMessageBubbles.kt:754-758`）。
4. **图片判定缓存按 `message.id`**，流式期间跳过扫描，结束后只算一次：
   `ChatMessageBubbles.kt:405-415`。
5. **Markdown 缓存 key = `assistant:<message.id>`**，不把长度编进 key（否则每帧缓存失效）：
   `ChatMessageBubbles.kt:490-496`；内部再用 `ChunkedText.isExtensionOf` 决定"续扫"还是"重置"（`IncrementalMarkdown.kt:16-26`）。
6. **"正在生成"占位项也是固定 key `assistant_generation_placeholder`**，不会随消息数量变化换身份：
   `ChatMessageList.kt:257-262`；判定只在"末条 id + 内容签名"变化时重算（`:134-148`）。

一句话：**消息 id 同时是持久化主键、消息树节点 id、Compose item key、缓存 key**。四者统一是这套 UI 在流式高频更新下不闪烁、不跳动的根本原因。

---

## 5. 与 Operit 的对比

### 5.1 "不中断"（同一轮内尽量不失败）

| 维度 | 太墟 | Operit（当前 `_operit-src`） |
|---|---|---|
| 层数 | 协议层 → ProviderRunner → TurnRunner → Loop，四层各自有自愈 | `AIService.sendMessage` 返回 `Stream<String>`，重试/解析基本压在 provider 实现内（`AIService.kt:60-74`） |
| 限流/网络 | 指数退避 + 大上下文动态重试预算 + 瞬态故障保底（`HarnessProviderRunner.kt:205-231, 366-401`） | 由各 provider 自行实现（接口上有 `enableRetry` 开关，`AIService.kt:71`） |
| 输出上限 | 有"扩容一次重放"的专门恢复（`ProviderOutputRecovery.kt:8-33`） | 无统一层，取决于 provider |
| 上下文溢出 | 识别 413 / `context_length_exceeded` → 紧急压缩 → 图片剥离 → 才失败（`HarnessProviderRunner.kt:269-323`） | 有上下文管理（压缩/摘要），但不在流接口这一层（`AIService` 只负责一次请求） |
| 空响应 | 明确分类 + 最多 2 次重试 + 长上下文先压缩（`HarnessProviderRunner.kt:324-365`） | `sendMessage` 不描述空响应语义 |
| 工具轮次 | 预算分段 + 自动续跑提示（`HarnessLoop.kt:1412-1429`） | 不在 `AIService` 契约内 |
| UI 稳定性 | 同 id 快照替换 + 固定 item key（见 `4） | 流是冷流 + 可回滚文本流（`SAVEPOINT/ROLLBACK`，`RevisableTextStream.kt:5-17`），UI 侧要有自己的版本/回滚状态机 |

结论：太墟把"不中断"实现为**引擎职责**（失败分类 + 自愈策略集中在 ProviderRunner/Loop）；Operit 把"不中断"下放给**provider 实现 + UI 状态机**，接缝更窄，但每条自愈策略都要在 provider 侧重复。

### 5.2 "中断后可恢复"

| 维度 | 太墟 | Operit |
|---|---|---|
| partial 归属 | 先进内存 StateFlow，取消时 `NonCancellable` 落 Room（`HarnessProviderRunner.kt:524-552`） | 先进内存 `SmartString`（`TextStreamRevisionTracker.kt:5-11`），取消时 `detachStreamingAiMessage(snapshot)` 抢救（`MessageProcessingDelegate.kt:496-562, 564-573`） |
| 网络错误后的半截内容 | 保留（`settleFailedStream` 落库，`HarnessProviderRunner.kt:72-83`）；或按错误类型先丢弃再整轮重生成 | 保留并标记中断（`MessageProcessingDelegate.kt:1513-1542`） |
| 进程死亡后 | operation 表 + durable task + 悬空工具调用规划 + 轮检查点，可自动续跑或明确挂起（`HarnessLoop.kt:388-494`、`:716-745`） | 接口层只有 `cancelStreaming()`（`AIService.kt:29`）；本次检索范围内未见 operation/durable task/轮级 checkpoint 等价物，恢复语义主要由 UI 侧 `chatRuntime` 承担 |
| 回退粒度 | 消息树任意 leaf + 每轮文件快照（`RewindController`、`CheckpointStore.MAX_KEPT=100`） | 文本级 `SAVEPOINT/ROLLBACK`（按字符下标，`TextStreamRevisionTracker.kt:13-24`） |
| 工具调用中断 | `DanglingToolCallPlanner` 区分 SAFE 重放与 stub（`HarnessLoop.kt:716-745`） | 未见等价规划器 |

结论：太墟的"中断后可恢复"建立在**持久化事实源**上——消息 id 先分配、partial 可落库、operation 与消息同事务，因此"进程死了"和"用户点了停止"走同一套消息语义。Operit 的恢复点更细（可回滚到任意文本 savepoint，markdown 流式渲染更完整），但事实源仍在内存 + UI 层，缺少跨进程的显式断点。

### 5.3 各自做对了什么

太墟：
1. **先定 id 再流式**：assistantId 在调用前生成，流式、落库、UI key、缓存 key 全部复用（`HarnessLoop.kt:1238`），天然幂等替换。
2. **失败分类与自愈集中**：每个 catch 分支都对应一种可行动恢复，而不是统一"重试三次"（`HarnessProviderRunner.kt:173-426`）。
3. **"绝不静默成功"**：空响应/提前 EOF/无工具调用都被显式判错（`ProviderStreamCompletion.kt:7-29`、`ProviderResponseNormalizer.kt:60-83`），避免前台"发消息没回复"。
4. **取消也落库**：`NonCancellable` + 失败时删气泡，保证"用户看到的就是存下来的"（`HarnessProviderRunner.kt:536-551`）。

Operit（值得保留/对照）：
1. **流式 Markdown 渲染链路更完整**：`StreamMarkdownRenderer` 支持 rollback 前缀与静态节点复用（`StreamMarkdownRenderer.kt:408-471`），太墟流式期间只是纯文本。
2. **回滚粒度到字符 savepoint**：`TextStreamRevisionTracker` 只存长度不存快照，内存友好（`TextStreamRevisionTracker.kt:13-17`）。
3. **取消快照是显式设计**：`readCurrentTurnCancellationSnapshot` 让"保留部分回复"成为可选项（`MessageProcessingDelegate.kt:496-513`），语义清晰。

---

## 6. 对移植（03-mapping）最关键的 6 个事实

1. 需要移植的最小闭环是：`ChatResult` + onDelta/onReasoning/onToolProgress 回调 + StreamBuffer + 一个按 id 覆盖消息的投影器；`TurnRunner/AgentTurnRunner` 是纯 Kotlin，可独立搬。
2. 若 Operit 保留 `Stream<String>` 作为 provider 接缝，需要额外包一层"按 16ms 产快照"的适配器（`ProviderClient.kt:685`、`StreamBuffer.kt:44-50`）。
3. `ChunkedText` 是纯 `core:model` 类（`ChunkedText.kt:12-118`），无 Android 依赖，可直接复制。
4. 流式期间的 UI 取舍必须显式决定：太墟选择"纯文本 + 结束后 Markdown"（`ChatMessageBubbles.kt:443-497`）；Operit 已有流式 Markdown，可直接复用，二者不可混用同一 item 渲染路径。
5. 取消语义要三处一起改才成立：socket 取消（`ProviderClient.kt:110`）、`NonCancellable` 落库（`HarnessProviderRunner.kt:536`）、UI 同 id 覆盖（`SessionMessageProjector.kt:184-198`）。
6. 若要"杀进程可恢复"，`OperationCoordinator` 的 `settleEffect` 同事务模型（`operation/OperationCoordinator.kt:254-277`）比消息本身更关键，属于移植的第二阶段，而不是第一阶段的 UI 工作。

---

## 附录 A · 关键常量

| 常量 | 值 | 位置 |
|---|---|---|
| 流式快照发布间隔 | 16 ms | `ProviderClient.kt:685` |
| 思考累积上限 | 128 × 1024 字符 | `ProviderClient.kt:675` |
| 空响应重试上限 | 2 | `HarnessProviderRunner.kt:573` |
| 输出上限恢复次数 | 1 | `HarnessProviderRunner.kt:572` |
| 默认最大工具轮数 | 200 | `HarnessLoop.kt:1649` |
| 实时消息窗口 | 600 条 / 8M 字符 | `SessionTreeStore.kt:345`、`SessionMessageProjector.kt:361` |
| 折叠检查点保留 | 100 轮 | `checkpoint/CheckpointStore.kt:255` |
| UI 流式纯文本截断 | 64,000 字符 | `ChatMessageBubbles.kt:575` |

## 附录 B · 本次未展开（留给 02/03）

- `ApiContextAssembler` 的完整上下文装配与压缩阶段（只在本文引用其与 `ContextWindowPolicy` 的接点）。
- 工具执行、审批门控、子智能体 lane 的细节（属于工具链路，不属于"输出链路"）。
- WebChat 等第二消费面（与主 UI 复用同一 `messagesForSession` 投影）。
