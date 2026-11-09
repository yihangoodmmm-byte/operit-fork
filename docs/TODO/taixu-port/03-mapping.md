# 03 · 太墟 → Operit 移植映射与分阶段方案

> 输入：`docs/TODO/taixu-port/01-taixu-output.md`、`docs/TODO/taixu-port/02-operit-seam.md`
> 本文只做设计与切分，不含实现代码，不修改任何 `.kt` / `.xml` / `.gradle`。
> 行号引用基于当前工作树快照；改代码后行号会漂移，以符号名为准。
> 约束：**不引入任何新依赖、不新增 Gradle 模块、不允许编译期修改构建脚本**。

---

## 0. 结论速览

1. **移植对象不是 Provider 重写**，而是"输出增量模型 + 消息事实源"两层契约。Operit 的 HTTP/SSE 解析、请求体拼装、多模态处理保留；改造集中在 3 个协议族基类 + 2 个装饰器 + 2 个本地实现。
   依据：`02` §1.4 实现清单、§5.1 可保留项。
2. **零新依赖是成立的**。Koin 用现有单例/构造注入替代；OkHttp 4.12 能覆盖太墟 5.5 用到的全部 API；Room 已在用（聊天），ObjectBox 只承载记忆向量，不承载聊天，不需要第二套存储。
3. **需要新增的只有类型，不是库**：`ChunkedText`（可原样复制，纯 Kotlin）、`ModelEvent`、`ModelTurnResult`、`StreamSnapshotBuffer`、`XmlTextProjection`。
4. **三阶段各自可独立编译、各自有可见效果**：阶段一只换"生产者内部怎么累积/节流/上报"，对外仍是 `Stream<String>`；阶段二换"UI 怎么消费"；阶段三换"输入怎么装配"。
5. **最大风险在持久化，不在流**：`MessageEntity` 与 `MessageVariantEntity` 双写 `content`，任何结构化字段都必须两表同步；`AppDatabase` 只注册显式 Migration（`AppDatabase.kt:516-538`，版本 21，`exportSchema=false`），迁移必须手写且可作用在旧备份上。

---

## 1. 类型映射表

### 1.1 总表

| # | 太墟类型 | 太墟位置 | Operit 现状 | 结论 | Operit 落点 |
|---|---|---|---|---|---|
| 1 | `ChatResult` | `harness/.../ChatResult.kt:3-17` | 无；`EnhancedAIService` 用字符串收敛 | **新建** `ModelTurnResult` | `core/chat/transport/` |
| 2 | `ChatUsage` | `ChatResult.kt` 同文件引用 | `ProviderUsageSnapshot`（`data/stats/ProviderUsageSnapshot.kt:32-44`） | **复用**，不新建 | `data/stats/` |
| 3 | `ApiToolCallSpec` | `ChatResult` 字段类型 | 无公共类型；`OpenAIProvider.ToolCallState` 为 private，出 Provider 即拍平成 XML | **新建** `ModelToolCall` | `core/chat/transport/` |
| 4 | `ToolCallStreamProgress` | `ToolCallStreaming.kt:16-54` | 无；UI 只能看到 XML 文本 | **新建** `ToolCallProgress` | `core/chat/transport/` |
| 5 | `ChunkedText` | `core/model/ChunkedText.kt:10-118` | `SmartString` + 长度式 `TextStreamRevisionTracker` | **复制**（纯 Kotlin，无 Android 依赖） | `core/chat/transport/ChunkedText.kt` |
| 6 | `StreamBuffer` | `StreamBuffer.kt:14-50`（16ms 快照） | 无；每个 chunk 直接 `emit` | **新建** `StreamSnapshotBuffer` | `core/chat/transport/` |
| 7 | `HarnessMessage`（sealed） | `HarnessMessage.kt:55-58` | `ChatMessage`（`sender/content` 字符串） | **不建平行模型**，沿用 `ChatMessage` | `data/model/ChatMessage.kt` |
| 8 | `AssistantText` | `HarnessMessage.kt:122-148` | `ChatMessage(sender="ai")` + `MessageEntity` | 映射：`textContent → content`，`reasoning → @Transient` 快照 | 同上 |
| 9 | `UserMessage(imageUrls)` | `HarnessMessage.kt` | `ChatMessage(sender="user")` + `AttachmentInfo` | 映射到阶段三的 `ContentPart` | `data/model/AttachmentInfo.kt` |
| 10 | `TurnOutcome` / `TurnProviderOutcome` | `TurnRunner.kt:8-11` | 无；循环在 `EnhancedAIService` 内以文本收尾 | **新建** `TurnSettlement`（阶段二引入） | `core/chat/transport/` |
| 11 | `NormalizedProviderResponse` | `ProviderResponseNormalizer.kt:10-59` | 无 | 取消该层，其"空响应判定"并入 `ModelTurnResult` | — |
| 12 | `SessionMessageProjector` | `projection/SessionMessageProjector.kt:179-266` | 无；UI 直接收 `Stream<String>` | **新建** `TranscriptProjector`（阶段二） | `core/chat/transport/` |
| 13 | `TextStreamEvent`(SAVEPOINT/ROLLBACK) | `RevisableTextStream.kt:5-13`（Operit 侧） | 已存在，且被四个层次复制 | **删除**（阶段二） | 现 `util/stream/` |
| 14 | `TextStreamRevisionTracker` | `TextStreamRevisionTracker.kt:5-29`（Operit 侧） | 已存在 | **删除**（阶段二） | 现 `util/stream/` |
| 15 | — | — | `PromptTurn`（`core/chat/hooks/PromptTurn.kt:26-31`） | **保留**，阶段三扩展为 parts 载体 | `core/chat/hooks/` |
| 16 | — | — | `AIService.sendMessage`（`AIService.kt:60-74`） | **保留签名**作为文本投影出口；新增 `ModelTurnService` | `api/chat/llmprovider/` |

### 1.2 需要新建的类型（建议签名）

```kotlin
package com.ai.assistance.operit.core.chat.transport

import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot

data class ModelToolCall(
    val id: String,
    val name: String,
    /** 完整 JSON 参数原文；与太墟一致，保证多轮原样回传。 */
    val argumentsJson: String,
)

data class ToolCallProgress(
    val callId: String,
    val name: String,
    val addedLines: Int,
    val deletedLines: Int,
    val argumentChars: Int,
)

data class ModelTurnResult(
    val content: String?,
    val toolCalls: List<ModelToolCall> = emptyList(),
    val reasoningContent: String? = null,
    /** 未上报时为 null，不构造"全 0"占位，避免统计层把未知当 0。 */
    val usage: ProviderUsageSnapshot? = null,
    val reasoningMs: Long? = null,
)
```

```kotlin
package com.ai.assistance.operit.core.chat.transport

/** 一轮生成的结构化事件流；替代"文本 + 旁路回滚事件"的组合。 */
sealed interface ModelEvent {
    val turnId: String

    data class TextSnapshot(
        override val turnId: String,
        val snapshot: ChunkedText,
    ) : ModelEvent

    data class ReasoningSnapshot(
        override val turnId: String,
        val snapshot: ChunkedText,
    ) : ModelEvent

    data class ToolProgress(
        override val turnId: String,
        val progress: ToolCallProgress,
    ) : ModelEvent

    data class Settled(
        override val turnId: String,
        val result: ModelTurnResult,
    ) : ModelEvent

    data class Failed(
        override val turnId: String,
        val message: String,
        /** 失败前已产出的最终快照；取消/中断时用于落库。 */
        val partial: ChunkedText,
    ) : ModelEvent
}
```

```kotlin
package com.ai.assistance.operit.core.chat.transport

/** 纯累积 + 节流发布；clock 可注入以便单测。 */
class StreamSnapshotBuffer(
    private val publishIntervalMs: Long = 16L,
    private val clock: () -> Long,
) {
    fun append(delta: String)
    fun snapshot(): ChunkedText
    /** 到点才返回快照，否则 null；最后一块必须 flush()。 */
    fun publishIfDue(): ChunkedText?
    fun flush(): ChunkedText
    /** 新一轮从同 lineage 继续；跨轮必须 new 实例。 */
    fun reset(): ChunkedText
}
```

```kotlin
package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.core.chat.transport.ModelEvent
import com.ai.assistance.operit.util.stream.Stream

/**
 * 与 AIService 并列，不改 AIService 现有签名。
 * 三种协议族基类 + 两个装饰器 + 本地实现分别实现它。
 */
interface ModelTurnService {
    suspend fun sendTurn(request: ModelTurnRequest): Stream<ModelEvent>
}

data class ModelTurnRequest(
    val context: android.content.Context,
    val turns: List<com.ai.assistance.operit.core.chat.hooks.PromptTurn>,
    val modelParameters: List<com.ai.assistance.operit.data.model.ModelParameter<*>>,
    val enableThinking: Boolean,
    val availableTools: List<com.ai.assistance.operit.data.model.ToolPrompt>?,
    val preserveThinkInHistory: Boolean,
    val enableRetry: Boolean,
    val turnId: String,
)
```

> `ModelTurnRequest` 只承接"一次请求 + 一个 turnId"；token/usage 回调仍在 `AIService` 上，由装饰器在 `sendTurn` 内部记账后随 `Settled.usage` 输出，不新增第二本账。

### 1.3 直接复用、不新建

| 现有类型 | 位置 | 复用理由 |
|---|---|---|
| `ProviderUsageSnapshot` | `data/stats/ProviderUsageSnapshot.kt:32-44` | 已含 uncached/cached/output/reasoning，比 `ChatUsage` 更细 |
| `PromptTurn` | `core/chat/hooks/PromptTurn.kt:26-31` | 输入侧契约不动；阶段三在其上叠加 parts |
| `AttachmentInfo` | `data/model/AttachmentInfo.kt:7-13` | 附件已有 filePath/mime/行内 content，阶段三转 `ContentPart` |
| `ChatMessage` / `MessageEntity` | `data/model/` | 消息事实源已在 Room；不引入太墟的 `payloadJson` 表 |
| `Stream` / `MutableSharedStream` | `util/stream/Stream.kt:71-107`、`HotStream.kt` | 新事件流仍可用它承载，渲染器也仍吃 `Stream<Char>` |
| `ModelParameter` / `ToolPrompt` | `data/model/` | 请求参数与工具描述结构已够用 |

### 1.4 必须删除（避免双语义并存）

- `TextStreamEvent` / `TextStreamEventType`（`RevisableTextStream.kt:5-13`）与全部 `withEventChannel` / `shareRevisable`。
- `TextStreamRevisionTracker` 及四处复制的 savepoint/rollback（`02` §3.3 表）。
- Provider 内 `emitRollback` 调用点（`OpenAIProvider.kt:1581-1588`、`ClaudeProvider.kt:1330-1335`、`GeminiProvider.kt:1205-1210`）。
- `RevisableTextStreamRemember` 与 `StreamMarkdownRenderer` 的 `StreamRollbackPrefix` 分支（`StreamMarkdownRenderer.kt:427-481`）。

> 删除时点由阶段决定，见 §3；在阶段二结束前，上述代码与新通道不共享任何数据路径。

---

## 2. 依赖与架构差异处理（逐条"不引入新依赖"做法）

### 2.1 Koin → Operit 无 DI（手动单例）

- 太墟：`harness/build.gradle.kts:27` 引入 `koin-core`，`di/harness/KoinModule.kt:103` 等以 `single/factory` 装配；`HarnessLoop` 的依赖从容器 `get()`。
- Operit：`object AIServiceFactory`（`AIServiceFactory.kt:219`）、`ChatHistoryManager.getInstance(context)`（`ChatHistoryManager.kt:93-108`）、`MultiServiceManager(context)` 由 `EnhancedAIService`（`EnhancedAIService.kt:361`）与 `MessageCoordinationDelegate`（`:109`）直接构造持有。
- **不引入 Koin 的做法**：新增的 `TranscriptProjector`、`StreamSnapshotBuffer`、`XmlTextProjection` 全部为**无状态或少状态类**，构造参数显式传入；需要跨请求存活的只在现有持有者上加字段（`ChatServiceCore` / `AIServiceFactory`），生命周期与它们一致。禁止新增第二个服务定位器。
- 副作用约束：`StreamSnapshotBuffer` 每轮新建（`turnId` 一对一的 lineage），不做全局缓存，避免"跨轮复用导致 isExtensionOf 判定错误"。

### 2.2 OkHttp 5.5.0 → 4.12.0

- 太墟：`gradle/libs.versions.toml:23` `okhttp = "5.5.0"`；测试用 `mockwebserver`。
- Operit：`gradle/libs.versions.toml:55` `okhttp = "4.12.0"`，已含 `okhttp-sse`（`:204`）。
- **不升级、不新增的做法**：
  1. 只移植 SSE 解析与请求构造**逻辑**，不移植依赖版本；
  2. OkHttp 4 的 `Response.body` 为可空，移植代码按 `response.body` 可空签名书写（`requireNotNull(body)` 属于入口校验）；
  3. 太墟用到的 `OkHttpClient` 超时/拦截器配置映射到 Operit 现有 client（`OpenAIProvider` 构造参数 `client: OkHttpClient`），不新建 client 池；
  4. `okhttp-sse` 两边都有，但 Operit 现有 Provider 是手写 `readUtf8Line` 解析；**保持手写解析**，不为引入 `EventSource` 改链路；
  5. 上游 `mockwebserver` 单测不随代码移植（Operit 未声明该依赖）；单测改成对 `StreamSnapshotBuffer` / `ChunkedText` 的纯 JVM 测试，需要 HTTP 级测试时由用户另行批准依赖。

### 2.3 Room vs ObjectBox

- Operit 实际分工：**聊天在 Room**（`AppDatabase.kt:28-36`：`ChatEntity`/`MessageEntity`/`MessageVariantEntity`/`TokenUsageRecordEntity`/`TokenStatsModelEntity`，版本 21）；**ObjectBox 只承载记忆/文档向量**（`data/db/ObjectBox.kt`、`Memory`、`DocumentChunk`、`MemoryRepository`）。
- 太墟：消息以 `payloadJson` 存 Room（`HarnessMessage.kt:55-58` 注释），并有 operation/lane 状态表。
- **不引入新依赖的做法**：
  1. 不新建数据库、不把聊天迁到 ObjectBox；
  2. 阶段一、二**零 schema 变更**；需要新增的结构化字段只在阶段三加列（`AppDatabase` 21→22 + `MIGRATION_21_22`），复用现有迁移链；
  3. 不移植 `operation` / durable task 表（属于第 4 阶段"进程级恢复"议题，超出本次三阶段范围）；
  4. `content` 仍是唯一人类可读正文列，结构化字段只做**附加列**，保证导出/搜索/网页聊天继续只读正文。

### 2.4 其它模块差异

| 差异 | 太墟 | Operit | 处理 |
|---|---|---|---|
| 模块 | `harness` 是 Android library，依赖 `ktor`、`jgit`、`rhino`、`plugin:*` | 单 `app` 模块 | 只复制纯 Kotlin 类型到 `app`，不建新模块、不复制 `build.gradle.kts` |
| 序列化 | `kotlinx.serialization` + 自定义 `ChunkedTextSerializer` | 已有 `kotlinx-serialization-json 1.9.0`（`libs.versions.toml:42`） | `ChunkedText` 复制后**去掉 @Serializable**（仅内存快照，不落库），消除对自定义 serializer 的需要 |
| 协程 | `kotlinx-coroutines` | 已有 | 直接用 |
| Android 版本 | — | `minSdk 26`（`app/build.gradle.kts:398`） | `ChunkedText` 无高版本 API；节流用 `SystemClock.elapsedRealtime` 或注入 clock |

---

## 3. 三阶段切分

**总原则**：每个阶段结束时代码库只有一个"生产者"和一个"消费者"，不存在按配置切换的两条路径；每个阶段独立构建、可整体撤销（以 git 提交为粒度，不依赖运行时代码分支）。

### 阶段一 · 替换输出流的增量模型（不动 UI 渲染器）

目标：把"字符串累积 + 每 chunk 直发"换成"ChunkedText 快照 + 16ms 节流 + 结构化 tool progress + 中断保留语义"，对外仍由 `AIService.sendMessage(...): Stream<String>` 输出 XML 文本，UI 一行不改。

可见效果：
- Claude / Gemini 断网或重试时，屏幕上的已产出内容**不再消失重打**，而是保留并追加中断说明（与 `OpenAIProvider.kt:3478-3501` 现有语义一致）；
- 长回复的 UI 提交频率由"每 chunk"收敛为 16ms 一帧；
- 工具参数进度以 `ToolCallProgress` 事件上报（本阶段仅日志可见）。

新增文件：
- `app/src/main/java/com/ai/assistance/operit/core/chat/transport/ChunkedText.kt`
- `.../core/chat/transport/ModelTurnResult.kt`（含 `ModelToolCall`、`TurnSettlement` 先不建）
- `.../core/chat/transport/ModelEvent.kt`
- `.../core/chat/transport/StreamSnapshotBuffer.kt`
- `.../core/chat/transport/XmlTextProjection.kt`（`Stream<ModelEvent> → Stream<String>`，工具调用在此投影为现有 `<tool_N>`/`<param>` 文本）
- `.../api/chat/llmprovider/ModelTurnService.kt`

修改文件：
- `api/chat/llmprovider/OpenAIProvider.kt`：`StreamEmitter`（1524-1601）内部改为 `StreamSnapshotBuffer`；删除 savepoint/rollback；catch 分支统一为"有正文则保留 + `Failed(partial)`"。
- `api/chat/llmprovider/ClaudeProvider.kt` / `GeminiProvider.kt`：同上，删除 `emitRollback`。
- `api/chat/llmprovider/TokenTrackingAIService.kt`：实现 `ModelTurnService`，转发事件并在 `Settled` 注入 usage。
- `api/chat/llmprovider/RateLimitedAIService.kt`：实现 `ModelTurnService` 时**逐事件转发**；这一条同时修掉 `02` §2.5 记录的"限流配置下事件通道丢失"缺陷（新链路不再依赖旁路通道，缺陷自然不存在）。
- `api/chat/EnhancedAIService.kt`：两处 `sendMessage` 调用点（1131、2375）改为消费 `ModelEvent`，并把 `XmlTextProjection` 的结果作为原返回值；上下文预算/工具循环/记忆更新逻辑不动。
- `api/chat/llmprovider/AIService.kt`：不改签名；只补文档注释说明 `sendMessage` 是文本投影出口。

不修改：`StreamMarkdownRenderer`、`CccMessageItem`、`MessageProcessingDelegate`、`ChatMessage`、任何 Room 表。

已知代价（必须写进验收记录）：工具 XML 标签在本阶段改为"调用确定后一次性投影"，UI 的工具气泡从"参数流式出现"变为"整调用出现"。

验收标准：
1. `:app:compileDebugKotlin` 通过（CI 执行，本文不运行构建）。
2. Claude/Gemini 断网用例：已显示文本保留、中断提示出现、无重复文本、无内容清空。
3. 连续 3 轮工具调用：最终 `content` 与阶段一前逐字节一致；工具调用顺序与 `Settled.toolCalls` 一致。
4. `StreamSnapshotBuffer` 单测：注入 clock，验证 16ms 内多次 append 只产 1 个快照；`flush()` 保证尾块不丢。
5. `ChunkedText` 单测：`isExtensionOf` 同 lineage 为真；跨轮为假；`subSequence` 边界。
6. 现有聊天读写回归：新建/切换/删除会话、图片消息、变体切换全部正常（本阶段无 schema 变更）。

---

### 阶段二 · 替换渲染消费端

目标：UI 不再消费 `Stream<String>` 增量，而是消费"同一消息 id 的 `ChunkedText` 快照"；`StreamMarkdownRenderer` 保持不动，由适配器把快照喂成 `Stream<Char>`。

可见效果：
- 流式消息不再出现"整段清空重打"或节点重建；`LazyColumn` item 身份稳定；
- 取消/失败时保留最后快照并落库；
- 悬浮窗、全屏模式、图片导出三条旁路一并切到快照源。

新增文件：
- `.../core/chat/transport/TurnSnapshotState.kt`（`turnId → ChatTurnSnapshot(content/reasoning/toolProgress)`，`StateFlow`）
- `.../core/chat/transport/TranscriptProjector.kt`（按 `message.id` 原地替换/追加，等价 `SessionMessageProjector`）
- `.../core/chat/transport/ChunkedTextCharStream.kt`（`ChunkedText` 新前缀 → 仅追加 `Char`，喂 `StreamMarkdownRenderer`）
- `.../core/chat/transport/TurnSettlement.kt`（`Complete/Continue/RoundLimit/Failed`）

修改文件：
- `data/model/ChatMessage.kt`：删除 `@Transient contentStream: Stream<String>`（31 行），改为 `@Transient val turnSnapshot: ChatTurnSnapshot?`；`Parcelable` 不写该字段。
- `services/ChatServiceCore.kt`（302-304）：`getResponseStream(): SharedStream<String>` → `getTurnSnapshot(chatId): StateFlow<ChatTurnSnapshot?>`。
- `services/core/MessageProcessingDelegate.kt`：删除 1231-1390 与 1738-1779 的 tracker/事件协程；落库改用 `Failed.partial` / `Settled.content`；取消路径（496-566）继续以最后快照落库。
- `core/chat/AIMessageManager.kt`（511-521）：删除 `shareRevisable`，改为订阅 `Stream<ModelEvent>`。
- `api/chat/EnhancedAIService.kt`：删除 1146-1187、2404-2433 的回滚转发；文本重拼改为 `TranscriptProjector`。
- UI 消费点：`ui/features/chat/v2/CccMessageItem.kt`（62-63、166-182）、`ui/features/chat/components/style/cursor/AiMessageComposable.kt`（174-195）、`ui/features/chat/components/style/bubble/BubbleAiMessageComposable.kt`（371-394、575-600）、`ui/floating/ui/fullscreen/viewmodel/FloatingFullscreenModeViewModel.kt`（215-233）、`ui/floating/ui/window/components/ChatMessages.kt`（30-46）、`ui/features/chat/util/MessageImageGenerator.kt`（256-258）——全部改读 `turnSnapshot`，渲染仍走 `StreamMarkdownRenderer`。
- 剩余 Provider：`MNNProvider`（独立实现，需补 `ModelTurnService`）与 `OpenCodeProvider`（`AIService by delegate`，需同步转发事件）；`CodexProvider` 继承 `OpenAIResponsesProvider`，随基类获得实现。本阶段把这三者全部纳入新链路，UI 才有快照可读。

删除文件/代码：
- `util/stream/RevisableTextStream.kt`、`ui/features/chat/components/RevisableTextStreamRemember.kt`、`util/stream/TextStreamRevisionTracker.kt`；
- `StreamMarkdownRenderer` 的 `StreamRollbackPrefix` 分支（427-481）与 `MarkdownProcessor.toCharStream` 的事件/前缀继承（84-102 的 92-100）；
- `ChatMarkupRegex` 不删（阶段一投影工具标签仍用它）。

验收标准：
1. 编译通过。
2. 流式过程中：DOM/节点不重建（在 `StreamMarkdownRenderer` 打点统计"节点 reset 次数"为 0）；无回滚闪烁。
3. 取消生成：DB 中消息 `content` 等于取消前最后快照全文，UI 与 DB 一致；再次进入会话显示相同内容。
4. 悬浮窗、全屏、图片导出三处输出与主聊天一致。
5. 外部消费者回归：`ExternalChatResponseSanitizer`、`WebChatHttpBridge`、`A2aTaskManager` 仍收到与阶段一完全一致的 XML 文本（由 `XmlTextProjection` 保证，本阶段不改它们）。
6. `grep -r "contentStream\|SAVEPOINT\|ROLLBACK\|RevisableTextStream"` 在 `app/src` 无命中（工具删除彻底）。

---

### 阶段三 · 替换输入层（composer / 附件）

目标：输入从"字符串 + 内嵌 XML 标记 + ChatHistory 拼装"改为"结构化请求（`ModelRequest` + `ContentPart`）"，composer 直接产出 parts，附件带类型进入请求体。

可见效果：
- 图片/文件附件以结构化 part 进入请求体（请求日志可见 `image`/`file` part，不再靠正文里的路径文本）；
- 工具调用/工具结果以结构化消息回传模型，不再被拼回 XML 再解析；
- 旧会话仍可打开、继续对话、重新生成。

新增文件：
- `.../core/chat/transport/ModelRequest.kt`（`ModelRequest`、`ModelMessage`、`ContentPart`：`Text`/`Image`/`File`）
- `.../core/chat/transport/ChatRequestAssembler.kt`（`List<ChatMessage>` + `List<AttachmentInfo>` + 系统提示 → `ModelRequest`）
- `.../core/chat/transport/AttachmentPartsCodec.kt`（parts 与 `partsJson` 互转，供持久化）

修改文件：
- `core/chat/hooks/PromptTurn.kt`：保留 `content`，新增 `parts: List<ContentPart>`（默认空，由 assembler 填写）。
- `core/chat/AIMessageManager.kt`（130）：`sendMessage(attachments: List<AttachmentInfo>)` 直接转 parts，不再把附件描述塞正文。
- `ui/features/chat/v2/CccComposer.kt`：发送时输出 `(text, parts)`；附件选择沿用 `AttachmentSelector` / `AttachmentChip`，只改数据形态。
- `services/core/MessageProcessingDelegate.kt`、`services/core/MessageCoordinationDelegate.kt`：透传 parts，不解析正文。
- `api/chat/EnhancedAIService.kt`：请求装配改为 `ChatRequestAssembler`；工具循环写回结构化 tool 消息，不再注入 `<tool_result>` 文本。
- 各 Provider 的 `createRequestBody`：从 `ModelRequest.parts` 生成多模态字段（OpenAI/Claude/Gemini 三条协议族各自映射）。
- `data/model/MessageEntity.kt`、`MessageVariantEntity.kt`：新增 `partsJson: String? = null`；`fromChatMessage`/`toChatMessage`/`applyTo` 同步。
- `data/db/AppDatabase.kt`：版本 21→22，新增 `MIGRATION_21_22`（`ALTER TABLE messages ADD COLUMN partsJson TEXT`、`ALTER TABLE message_variants ADD COLUMN partsJson TEXT`），注册进 `addMigrations`。
- `data/repository/ChatHistoryManager.kt`：写入/读取时带上 `partsJson`。

验收标准：
1. 编译通过。
2. 图片 + 文本混合发送：请求体包含 image part（用现有请求日志/抓包断言），模型回复正常。
3. 一轮工具调用往返：模型收到的 tool_result 是结构化消息；DB 中 `content` 仍是可读文本。
4. 旧数据：用阶段二备份的数据库升级到 22，全部会话可打开；消息正文与升级前逐条一致；继续对话正常。
5. 变体切换：切换后附件/parts 与 `content` 同步（`MessageVariantEntity.applyTo` 覆盖验证）。
6. 备份/恢复：`RoomDatabaseBackupManager` 产物在 22 版本下可恢复；用 21 版本备份恢复后首次打开完成 21→22 迁移且数据完整。

---

### 阶段依赖与并行边界

| 阶段 | 依赖 | 可并行工作 | 交付物 |
|---|---|---|---|
| 一 | 无 | 类型层单测、投影器单测 | 事件通道 + 文本投影，UI 不变 |
| 二 | 阶段一（`ModelEvent` 定型） | 三处 UI 消费点可分头改 | UI 改吃快照，回滚机制删除 |
| 三 | 阶段二（消息快照/落库路径稳定） | 三条协议族的请求体映射可分头改 | 结构化输入 + Room 22 |

阶段一与阶段三的"类型定义"可以提前并行评审，但**落盘 schema 只能在阶段三动**。

---

## 4. 风险清单（持久化与旧数据）

| # | 风险 | 触发点 | 影响 | 必须做的事 |
|---|---|---|---|---|
| R1 | Room 版本升级后旧安装包无法打开 | 阶段三 `AppDatabase` 21→22 | 用户装回旧 APK 时数据库版本更高，旧代码判定不可用 | 发布说明写明"升级后数据库版本高于旧安装包，旧包无法覆盖安装"；由用户确认（见 §6） |
| R2 | `MessageEntity.content` 与结构化字段不同步 | 阶段三新列 | 导出、搜索、网页聊天、分享只读 `content`，会丢工具调用结构 | `content` 继续写人类可读 XML/文本全文；结构化列只做附加，不做唯一来源 |
| R3 | 变体表漏写 | `MessageVariantEntity`（`fromChatMessage`/`applyTo`） | 切换历史版本后附件/工具调用丢失 | 阶段三所有新列在两张表同步写入；单测覆盖"切换变体后 parts 一致" |
| R4 | 取消路径丢内容 | 阶段二删除 `contentStream` | `MessageProcessingDelegate` 496-566 的取消抢救依赖流对象 | 同一次提交内改用 `Failed.partial`；验收第 3 条强制检查 DB 与 UI 一致 |
| R5 | 旧备份恢复失败 | `RoomDatabaseBackupManager` / 21 备份恢复到 22 代码 | 恢复后首次打开需跑 21→22；SQL 写错会直接打开失败 | `MIGRATION_21_22` 手写并在 21 备份副本上实测；`exportSchema=false`（`AppDatabase.kt:36`）意味着没有 schema 校验文件可依赖 |
| R6 | 把聊天塞进 ObjectBox | 误判 `ObjectBox` 为聊天库 | 两库无跨库事务，崩溃后聊天与记忆不一致；备份只覆盖 Room 文件 | 明文规定：ObjectBox 仅限 Memory/DocumentChunk，聊天只进 Room |
| R7 | 外部协议断代 | 阶段二删除文本流 | WebChat SSE / A2A / 外部通道按 XML 文本设计 | 保留 `XmlTextProjection` 作为对外出口；改协议须单独立项 |
| R8 | 结构化后仍回注 XML | 阶段三工具循环 | 出现"结构化 tool_call + XML tool_result"混用，配对失败（参见 `docs/TODO/claude_tool_result_pairing_repair_20260907`） | 阶段三必须由同一个 `ChatRequestAssembler` 统一产出，工具结果只走结构化消息 |
| R9 | 阶段二 UI 与阶段一投影双跑 | 提交边界不清 | 同一条消息两路写入，出现重复文本 | 每个阶段以独立分支/提交收口；阶段二合并前 `grep` 确认 `contentStream` 已零引用 |
| R10 | 群聊/角色卡/子代理旁路 | `ConversationService`、`SubagentRunner`、群组编排 | 这些路径也调 `EnhancedAIService`，改输入层时容易漏 | 阶段三验收增加：群聊一轮、子代理一次、角色卡一条各跑通 |

---

## 5. 明确不做

- 不移植太墟的 `OperationCoordinator` 事务模型、durable task、进程重启续跑（属于第 4 阶段，需单独评估）。
- 不移植太墟的 `CheckpointStore` 文件检查点与 `RewindController`。
- 不引入 Koin / Ktor / JGit / Rhino / MockWebServer。
- 不新建 Gradle 模块，不改构建脚本；不在本文阶段内执行编译。
- 不保留任何"文本回滚重发"逻辑；中断语义统一为"保留已产出 + 明确状态"。

---

## 6. 待用户/Lead 拍板

1. **Room 21→22 的升级代价**：是否接受"升级后不能装回旧安装包"？（R1）
2. **对外协议**：WebChat/A2A 是继续输出 XML 文本，还是同步改结构化协议？默认按 §4 R7 保持 XML。
3. **阶段二删除节奏**：是否允许同一次发布内删除 `SAVEPOINT/ROLLBACK` 全套（推荐，避免双语义），还是分两次发布？
4. **本地 Provider（MNN / OpenCode）**：阶段二是否纳入？若不纳入，UI 切换后它们无法提供快照，因此本文按"纳入"设计；`CodexProvider` 继承基类，无单独工作量。
