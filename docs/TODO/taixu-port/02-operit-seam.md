---
For_Agent: 本文是 taixu-port 系列的只读剖析文档，不是最终移植方案。所有行号基于当前 _operit-src 工作树；改代码后行号会漂移。
---

# 02 · Operit AI IO 接缝剖析

## 0. 结论先行

Operit 的"AI 输出层"并不是一个带类型的增量事件流，而是三段拼起来的东西：

1. 一条冷的 `Stream<String>`，每个元素是一段**已经格式化好的文本**，其中内嵌 `<think>`、`<tool>`、`<param>`、`<tool_result>`、`<meta>` 等标记。
2. 一条旁路的 `SharedStream<TextStreamEvent>`，只承载 `SAVEPOINT` / `ROLLBACK` 两种修正指令。
3. 一串在四个层次重复实现的 revision tracker，用来把"文本增量"重新拼成"当前完整内容"。

因此移植太墟时，真正的切口不在 Provider，而在 `sendMessage` 的返回契约和"文本即协议"这个假设。输入层接口可以保留，Markdown/XML 渲染器也可以保留；必须替换的是修订事件与回滚重发机制，以及所有依赖"原始 XML 文本"的外部消费者。

三个最关键的判断：

- 输出层的公共契约是 `Stream<String>` 加旁路事件，不是结构化事件；`ChatMessage.contentStream` 把它一路带进 UI，换契约会穿透 15 个以上文件。
- "回滚重发"至少跨 10 个文件、二十余处读写点；而且语义已经分裂：OpenAIProvider 已改成"中断保留已产出内容"，Claude/Gemini 仍是"回滚到 0 后重发"。更糟的是，配置了限流/并发的模型会经过 `RateLimitedAIService`，该装饰器重新包了一层普通 `stream`，把 `TextStreamEventCarrier` 丢掉（`RateLimitedAIService.kt:31-121`），所以这条回滚链在某些配置下根本到不了 UI。
- 可原样保留的比例不低：Markdown 节点模型、原生分块器、Canvas 渲染、自定义 XML 渲染器都与传输层解耦，前提是继续给它们 `Stream<Char>`。

---

## 1. 输入层：发请求的接口契约

### 1.1 最底层接口 AIService

定义在 `app/src/main/java/com/ai/assistance/operit/api/chat/llmprovider/AIService.kt`。注意实际路径在 `llmprovider/` 下，不在 `api/chat/AIService.kt`。

```kotlin
interface AIService {
    val inputTokenCount: Long
    val cachedInputTokenCount: Long
    val outputTokenCount: Long
    val providerModel: String              // "DEEPSEEK:deepseek-chat"
    fun resetTokenCounts()
    fun cancelStreaming()

    suspend fun getModelsList(context: Context): Result<List<ModelOption>>

    suspend fun sendMessage(
        context: Context,
        chatHistory: List<PromptTurn> = emptyList(),
        modelParameters: List<ModelParameter<*>> = emptyList(),
        enableThinking: Boolean = false,
        stream: Boolean = true,
        availableTools: List<ToolPrompt>? = null,
        preserveThinkInHistory: Boolean = false,
        onTokensUpdated: suspend (input: Long, cachedInput: Long, output: Long) -> Unit = { _, _, _ -> },
        onUsageReported: (suspend (ProviderUsageSnapshot, attempt: Int) -> Unit)? = null,
        onNonFatalError: suspend (error: String) -> Unit = {},
        enableRetry: Boolean = true,
        recordTokenUsage: Boolean = true,
        onUsageFinalized: (suspend (attempt: Int?) -> Unit)? = null,
    ): Stream<String>

    suspend fun testConnection(context: Context): Result<String>
    suspend fun calculateInputTokens(
        chatHistory: List<PromptTurn>,
        availableTools: List<ToolPrompt>? = null
    ): Long
    fun release() {}
}
```

契约位置：`AIService.kt:12-104`；`sendMessage` 在 `AIService.kt:60-74`；token 计数在 `AIService.kt:13-26`；模型列表与连接测试在 `AIService.kt:37`、`AIService.kt:82`。

值得注意的契约细节：

- 即使 `stream=false`，返回值仍然是 `Stream<String>`，见注释 `AIService.kt:46` 与 `AIService.kt:58`。非流式只是"一次性灌进流"。
- `onUsageReported` 是统计账本通道，允许同一 attempt 多次覆盖；`onUsageFinalized` 只在成功时回调，见 `AIService.kt:49-54`。
- `cancelStreaming()` 是**实例级**取消，没有 request id；并发请求的取消语义由上层 `RateLimitedAIService` 的 epoch 补偿，见 `RateLimitedAIService.kt:14-27`。
- `calculateInputTokens` 让上层在发请求前做上下文窗口预算，见 `AIService.kt:91-94`。

### 1.2 输入数据类型

```kotlin
enum class PromptTurnKind { SYSTEM, USER, ASSISTANT, TOOL_CALL, TOOL_RESULT, SUMMARY }

data class PromptTurn(
    val kind: PromptTurnKind,
    val content: String,
    val toolName: String? = null,
    val metadata: Map<String, Any?> = emptyMap()
)

data class ModelParameter<T>(
    val id: String, val name: String, val apiName: String,
    val description: String, val defaultValue: T, val currentValue: T,
    val isEnabled: Boolean, val valueType: ParameterValueType,
    val minValue: Any?, val maxValue: Any?, val category: ParameterCategory,
    val isCustom: Boolean
)

data class ToolPrompt(
    val name: String,
    val description: String,
    val parameters: String = "",
    val parametersStructured: List<ToolParameterSchema>? = null,
    val details: String = "",
    val notes: String = ""
)
```

出处：`core/chat/hooks/PromptTurn.kt:3-31` 与 `PromptTurn.kt:60-113`；`data/model/ModelParameter.kt:6-19`；`data/model/ToolPrompt.kt:18-25`。

关键点：工具调用在请求侧是**结构化参数** `ToolParameterSchema`（`ToolPrompt.kt:6-12`），但在响应侧被 Provider 降维成 XML 文本，见第 2.2 节。这是输入/输出不对称的根源。

### 1.3 面向业务的门面 EnhancedAIService

```kotlin
class EnhancedAIService private constructor(private val context: Context) {
    data class SendMessageOptions(
        var message: String = "",
        var maxTokens: Int = 0,
        var tokenUsageThreshold: Double = 0.0,
        var chatId: String? = null,
        var chatHistory: List<PromptTurn> = emptyList(),
        var workspacePath: String? = null,
        var workspaceEnv: String? = null,
        var functionType: FunctionType = FunctionType.CHAT,
        var promptFunctionType: PromptFunctionType = PromptFunctionType.CHAT,
        var enableThinking: Boolean = false,
        var enableMemoryAutoUpdate: Boolean = true,
        var onNonFatalError: suspend (error: String) -> Unit = {},
        var onTokenLimitExceeded: (suspend () -> Unit)? = null,
        var customSystemPromptTemplate: String? = null,
        var isSubTask: Boolean = false,
        // ... 角色、群组编排、模型覆盖等字段
        var stream: Boolean = true,
        var disableWarning: Boolean = false
    )

    suspend fun sendMessage(options: SendMessageOptions): Stream<String>
}
```

出处：`EnhancedAIService.kt:100-104`、`EnhancedAIService.kt:328-358`、`EnhancedAIService.kt:906-908`。

这一层在 `sendMessage` 内部把 Provider 的流再包一层 `stream { ... }`（`EnhancedAIService.kt:966-967`），并把 Provider 的旁路事件原样转发到自己的 `eventChannel`（`EnhancedAIService.kt:1166-1167`、`EnhancedAIService.kt:2412-2413`），最后从 `EnhancedAIService.kt:1336` 返回 `wrappedStream.withEventChannel(eventChannel)`。

### 1.4 实例装配链

```
AIServiceFactory.createService(config)
  └─ TokenTrackingAIService(delegate = rawService)        AIServiceFactory.kt:269-280
       └─ OpenAIProvider / ClaudeProvider / GeminiProvider / ...
            （均由同一个 open class OpenAIProvider 派生，见下）

MultiServiceManager.createService
  └─ rawService = AIServiceFactory.createService(...)     MultiServiceManager.kt:316-320
       └─ 可选 RateLimitedAIService(delegate = rawService) MultiServiceManager.kt:349-353
```

Provider 基类签名：`OpenAIProvider.kt:88-103`，包含 `apiEndpoint`、`apiKeyProvider`、`modelName`、`OkHttpClient`、`providerType`、多模态开关、`enableToolCall`、`thinkingConfigurations` 等。

继承 `AIService` 的实现清单（按文件）：

| 文件 | 角色 |
| --- | --- |
| `llmprovider/OpenAIProvider.kt` | OpenAI 兼容协议基类，`open class` |
| `llmprovider/OpenAIResponsesProvider.kt` | Responses API 变体 |
| `llmprovider/ClaudeProvider.kt` | Anthropic Messages 协议 |
| `llmprovider/GeminiProvider.kt` | Gemini 协议 |
| `llmprovider/DeepseekProvider.kt` | OpenAI 派生，额外处理 reasoning |
| `llmprovider/QwenAIProvider.kt`、`KimiProvider.kt`、`OllamaProvider.kt`、`MistralProvider.kt`、`NvidiaAIProvider.kt`、`DoubaoAIProvider.kt`、`FourRouterProvider.kt`、`OpenRouterProvider.kt`、`XaiProvider.kt`、`OpenCodeProvider.kt` | OpenAI 派生 |
| `llmprovider/TokenTrackingAIService.kt` | 统计装饰器，`AIService` 实现 |
| `llmprovider/RateLimitedAIService.kt` | 限流装饰器，Kotlin 委托 `AIService by delegate` |

### 1.5 输入层调用方

输入层调用方（直接调 `AIService.sendMessage`，或经 `EnhancedAIService` 门面；按调用点）：

| 调用方 | 位置 | 用途 |
| --- | --- | --- |
| `EnhancedAIService.kt` | 1131、2375 | 主聊天与工具结果后的续轮 |
| `core/chat/AIMessageManager.kt` | 484 | 调 EnhancedAIService 而非 AIService，见下 |
| `api/chat/enhance/ConversationService.kt` | 276、364、1177、1237、1295、1339 | 摘要、标题、翻译、多模态探测 |
| `api/chat/library/MemoryLibrary.kt` | 257、660 | 记忆抽取/更新 |
| `core/tools/agent/PhoneAgent.kt` | 624 | 手机代理子循环 |
| `core/tools/defaultTool/standard/StandardFileSystemTools.kt` | 192 | 文件内容 AI 处理 |
| `core/agent/SubagentRunner.kt` | 53 | 子代理（走 EnhancedAIService） |
| `services/core/MessageCoordinationDelegate.kt` | 1063 | 计划器模型 |
| `llmprovider/ModelConfigConnectionTester.kt` | 107、162 | 连接与工具调用探测 |
| `llmprovider/TokenTrackingAIService.kt` | 95、111 | 装饰器转发 |
| `llmprovider/RateLimitedAIService.kt` | 92 | 装饰器转发 |
| `llmprovider/DeepseekProvider.kt` | 554 | `super.sendMessage` 转发 |
| `llmprovider/QwenAIProvider.kt` | 123 | `super.sendMessage` 转发 |
| `llmprovider/KimiProvider.kt` | 496 | `super.sendMessage` 转发 |
| `llmprovider/OpenCodeProvider.kt` | 70 | 委托给内部 provider |

另外 `ConversationService.kt` 内还有多处调用（1339 附近继续出现），属于同一模式。

其中 `AIMessageManager.kt:484` 与 `SubagentRunner.kt:53` 调的是 `EnhancedAIService.sendMessage`；其余大多数直接调 `AIService.sendMessage`。两者返回类型相同，因此输出层替换时都要改。

---

## 2. 输出层：流回 UI 的接口契约

### 2.1 表面契约：`Stream<String>`

`sendMessage` 返回 `Stream<String>`（`AIService.kt:74`），但类型系统没有表达任何语义。实际约定是：

- 每个元素是一段**无结构文本片段**，长度、切分点由 Provider 决定。
- 全部片段按发射顺序拼接，等于本轮的"显示文本"。
- 片段里混有最终正文、思考块、工具调用、工具结果、状态提示、HTML、LaTeX 等，全部用 XML 风格标签表达。
- 流的结束代表"本轮这一跳结束"，不代表整个 Agent 回合结束；工具循环会继续往同一条流里 `emit`（`EnhancedAIService.kt:1815-1841`、`EnhancedAIService.kt:2465`）。
- 错误通过协程异常结束流，错误文本可能被包装成用户可读消息；真正的中断提示由 Provider 直接 `emitTag` 写入正文（`OpenAIProvider.kt:3497-3498`）。

```kotlin
// 输出层的最小契约，等价于：
interface AIService {
    suspend fun sendMessage(...): Stream<String>   // 文本增量 + 内嵌标记协议
}
```

### 2.2 隐含协议：文本就是 XML 标记流

Provider 把结构化 tool call 转换成 XML 再发进同一条文本流：

| 标记 | 生产位置 |
| --- | --- |
| `<think>...</think>` | `OpenAIProvider.kt:2893`、`2904`、`2929`、`3132`、`2016` |
| 随机工具标签 `<tool_NNN ...>` | `OpenAIProvider.kt:1419`、`1509`，正则见 `ChatMarkupRegex.kt:10-12` |
| `<param name="...">...</param>` | `OpenAIProvider.kt:2062` |
| `<meta ...>` 元数据行 | `OpenAIProvider.kt:1568-1572`、`2397`、`2703`、`2725` |
| 工具结果 `<tool_result>` | 解析入口 `OpenAIProvider.kt:1780-1810` |

这条协议由 `ChatMarkupRegex` 同时用于生产与消费：生产侧见 `OpenAIProvider.kt:1419`，消费侧见 `OpenAIProvider.kt:1730-1804` 与 `ChatMarkupRegex.kt:27-95`。

后果是：Provider 的原生 tool call 结构在离开 Provider 时就被"拍平"了。UI、数据库、外部 SSE 拿到的都是文本，而不是 `ToolCall` 对象。

### 2.3 旁路契约：`TextStreamEvent`

```kotlin
data class TextStreamEvent(
    val eventType: TextStreamEventType,
    val id: String
)

enum class TextStreamEventType { SAVEPOINT, ROLLBACK }

interface TextStreamEventCarrier {
    val eventChannel: SharedStream<TextStreamEvent>
}

interface RevisableTextStream : Stream<String>, TextStreamEventCarrier
```

定义：`util/stream/RevisableTextStream.kt:5-28`。它不是 `Stream<String>` 的一部分，而是额外挂在对象上的属性；下游必须 `as? TextStreamEventCarrier` 才能看到（首次出现在 `EnhancedAIService.kt:1146`）。

三个 Provider 都实现了这对事件：

| Provider | 建通道 | SAVEPOINT | ROLLBACK |
| --- | --- | --- | --- |
| OpenAI | `OpenAIProvider.kt:3230` | `3253-3254` | `1581-1588`、`3511` |
| Claude | `ClaudeProvider.kt:1314` | `1323`、`1401` | `1330-1335`、`2009` |
| Gemini | `GeminiProvider.kt:1179` | `1199`、`1222` | `1205-1210`、`1327` |

### 2.4 输出层到 UI 的完整链路

```
Provider.sendMessage                    Stream<String> + eventChannel
  └─ TokenTrackingAIService.wrapStream  保留 eventChannel（TokenTrackingAIService.kt:173-183）
  └─ RateLimitedAIService.sendMessage   重新 stream{ collect 转发 }，但不复制 eventChannel（见 2.5）
  └─ EnhancedAIService.sendMessage      再包一层 Stream，并转发事件（EnhancedAIService.kt:966-967, 1166-1167, 1336）
  └─ AIMessageManager.sendMessage       .shareRevisable(...) 变成热流（AIMessageManager.kt:511-521）
  └─ MessageProcessingDelegate          主订阅读文本；旁路读事件（MessageProcessingDelegate.kt:1231-1390）
  └─ ChatRuntime.responseStream         （MessageProcessingDelegate.kt:203-205, 1128-1129）
  └─ ChatMessage.contentStream          （ChatMessage.kt:31）
  └─ rememberRevisableTextStream        把 ROLLBACK 转成"替换流 + 前缀"（RevisableTextStreamRemember.kt:27-95）
  └─ Stream<String>.toCharStream()      字符化并继承事件通道与前缀（MarkdownProcessor.kt:84-102）
  └─ StreamMarkdownRenderer(Stream<Char>)（StreamMarkdownRenderer.kt:380-393）
```

### 2.5 装配层的断点：RateLimitedAIService 会丢事件通道

`RateLimitedAIService.sendMessage` 的实现是新建一个普通 `stream { ... }`（`RateLimitedAIService.kt:47`），把 `delegate.sendMessage(...)` 的文本逐块 `collect` 后重新 `emit`（`RateLimitedAIService.kt:92-112`）。它没有检查 `delegate` 是否实现了 `TextStreamEventCarrier`，也没有 `withEventChannel`。

后果：

- 当模型配置启用 `requestLimitPerMinute` 或 `maxConcurrentRequests` 时，`MultiServiceManager` 会套上 `RateLimitedAIService`（`MultiServiceManager.kt:322-353`）。
- 上层的 `responseStream as? TextStreamEventCarrier` 会得到 `null`（`EnhancedAIService.kt:1146`、`2404`），于是 `revisionJob` 不会启动（`EnhancedAIService.kt:1163-1187`）。
- `AIMessageManager.shareRevisable` 也看不到 carrier，只共享文本（`AIMessageManager.kt:511-521`、`RevisableTextStream.kt:169-176`）。
- 最终 UI 侧 `rememberRevisableTextStream` 直接返回原流（`RevisableTextStreamRemember.kt:29`），`StreamMarkdownRenderer` 的 `rollbackPrefix` 永远是 `null`。

也就是说，"回滚重发"在默认直连配置下生效，在启用限流/并发的配置下静默失效。这是接缝分析里必须记录的一条非对称行为。

### 2.6 输出层消费方清单

换掉输出层时，下面这些文件必须同步改或加适配器。

A. 直接读 `contentStream` / `responseStream` 的运行时：

| 文件 | 位置 | 依赖方式 |
| --- | --- | --- |
| `services/core/MessageProcessingDelegate.kt` | 356-358、1128-1129、1231-1390、1697-1779 | 主文本收集、持久化、自动朗读、Waifu 分段、revision |
| `data/model/ChatMessage.kt` | 31 | `@Transient var contentStream: Stream<String>?` |
| `services/ChatServiceCore.kt` | 302-304 | 暴露 `getResponseStream(): SharedStream<String>?` |
| `data/repository/ChatHistoryManager.kt` | 1175 | 以 `contentStream == null` 判断是否为历史消息 |
| `ui/features/chat/components/RevisableTextStreamRemember.kt` | 27-95 | 事件转显示流 |
| `ui/floating/ui/fullscreen/viewmodel/FloatingFullscreenModeViewModel.kt` | 215-233 | 悬浮全屏逐字处理 |
| `ui/floating/ui/window/components/ChatMessages.kt` | 30-46 | 悬浮窗选择是否播流 |
| `ui/features/chat/util/MessageImageGenerator.kt` | 256-258 | 截图前清空 `contentStream` |

B. 渲染入口：

| 文件 | 位置 |
| --- | --- |
| `ui/features/chat/v2/CccMessageItem.kt` | 62-63、166-182 |
| `ui/features/chat/components/style/cursor/AiMessageComposable.kt` | 174-195 |
| `ui/features/chat/components/style/bubble/BubbleAiMessageComposable.kt` | 371-394、575-600 |
| `ui/common/markdown/StreamMarkdownRenderer.kt` | 380-393、874-887 |
| `ui/common/displays/MarkdownTextComposable.kt` | 37 |
| `ui/features/preview/CccDocumentViewer.kt` | 128 |
| `ui/features/chat/webview/workspace/WorkspaceManager.kt` | 147 |
| `ui/common/composedsl/ToolPkgComposeDslScreen.kt` | 2167 |

C. 把流当纯文本协议输出的外部消费者：

| 文件 | 位置 | 问题 |
| --- | --- | --- |
| `integrations/externalchat/ExternalChatResponseSanitizer.kt` | 29-41、50-80 | 用 `StreamXmlPlugin` 剥离 XML，默认输出假设是 XML 文本 |
| `integrations/http/WebChatHttpBridge.kt` | 1159-1196 | SSE 直接转发片段 |
| `integrations/a2a/A2aTaskManager.kt` | 139-147 | A2A 直接累积片段 |
| `core/tools/defaultTool/standard/StandardChatManagerTool.kt` | 73、1939-1949、2004-2007、2133-2156 | 工具侧消费响应流，Waifu 分支另起一条 |
| `util/WaifuMessageProcessor.kt` | 194-240、242-280 | 再分块、去 XML、TTS |
| `ui/floating/ui/fullscreen/XmlTextProcessor.kt` | 25-40 | 去 XML 得纯文本 |

---

## 3. 流类型语义与渲染器耦合点

### 3.1 `Stream` / `SharedStream` / `MutableSharedStream`

```kotlin
interface Stream<T> {
    val isLocked: Boolean
    val bufferedCount: Int
    suspend fun lock()
    suspend fun unlock()
    fun clearBuffer()
    suspend fun collect(collector: StreamCollector<T>)
}

interface SharedStream<T> : Stream<T> {
    val subscriptionCount: Int
    val replayCache: List<T>
    val completionCause: Throwable?
}

interface MutableSharedStream<T> : SharedStream<T> {
    suspend fun emit(value: T)
    fun tryEmit(value: T): Boolean
    fun resetReplayCache()
}
```

出处：`util/stream/Stream.kt:71-107`、`util/stream/HotStream.kt:13-49`。

语义要点：

- `Stream` 是冷流，且 `stream { }` 构建器只保存一个 `activeCollector`（`StreamBuilders.kt:112-125`）。同一个冷流被两个协程收集时，后者的 collector 会覆盖前者，因此多订阅必须先 `share`。这正是 `AIMessageManager.kt:511` 必须 `shareRevisable` 的原因。
- `Stream.share` 用 `MutableSharedStreamImpl` 启动上游 Job，并在结束时 `close(completionCause)`（`HotStream.kt:319-398`）。`completionCause` 只能被主订阅者读取；旁路订阅传 `propagateCompletionCause=false`（`AIMessageManager.kt:514-516`、`HotStream.kt:201-215`）。
- `MutableSharedStreamImpl.collect` 先重放 `replayCache`，再订阅实时通道（`HotStream.kt:169-225`）。replay 设为 `Int.MAX_VALUE` 时等于把整轮文本常驻内存（Provider 与 `EnhancedAIService` 都这么做，见 `OpenAIProvider.kt:3230`、`EnhancedAIService.kt:966`）。
- `lock/unlock` 用于"暂停接收但继续发送"的背压，冷流基类 `AbstractStream` 用 `ConcurrentLinkedQueue` 缓存（`Stream.kt:110-188`）；热流实现为空动作（`HotStream.kt:86-103`）。

### 3.2 SAVEPOINT / ROLLBACK 的语义

```kotlin
fun savepoint(id: String)          // 记录"当前内容长度"
fun rollback(id: String): SmartString?   // 把内容截断到该长度，并删除更晚的 savepoint
```

实现：`util/stream/TextStreamRevisionTracker.kt:5-29`。

- savepoint 只存一个 `Int` 长度，不存快照。注释明确这是为了内存，见 `TextStreamRevisionTracker.kt:13-17`。
- rollback 是"丢弃后缀"，不是"替换中间段"；截断后所有长度大于该值的 savepoint 都被移除（`TextStreamRevisionTracker.kt:19-24`）。
- 因此该机制只支持"回到请求起点重来"，不支持局部重写。

Provider 侧语义：

- OpenAI 的 savepoint 在重试循环之前打一次，id 是 `attempt_<uuid>`（`OpenAIProvider.kt:3253-3254`）。`emitRollback` 把 `receivedContent` 截到 savepoint 长度（通常 0），再发事件（`OpenAIProvider.kt:1581-1588`）。
- Claude/Gemini 的 `emitRollback` 直接把 `receivedContent` 清 0（`ClaudeProvider.kt:1330-1335`、`GeminiProvider.kt:1205-1210`），因为它们的 savepoint 也只有 0 这一个位置。

### 3.3 四个层次的 revision tracker

同一套"savepoint + append + rollback"逻辑被复制了四份：

| 层次 | 文件:行 | 用途 |
| --- | --- | --- |
| Provider | `OpenAIProvider.kt:1524-1601` | 维护 `receivedContent` 与 savepoint 长度 |
| 业务门面 | `EnhancedAIService.kt:1153-1187`、`2405-2433` | 维护 `streamBuffer` 与 `roundManager` |
| 消息服务 | `MessageProcessingDelegate.kt:1231-1351`、`1738-1779` | 维护持久化内容与最终 `aiMessage.content` |
| UI | `RevisableTextStreamRemember.kt:36-71` | 维护显示流与替换流 |

这四份必须同步，否则出现"UI 已回滚、数据库没回滚"或反之。`resolveFinalContent` 用"事件通道是否为空"来决定最终内容取哪份，正是这种耦合的证据（`MessageProcessingDelegate.kt:360-371`）。

### 3.4 渲染器与回滚的耦合点

`StreamMarkdownRenderer` 的输入是 `Stream<Char>`，不是 `Stream<String>`：

```kotlin
@Composable
fun StreamMarkdownRenderer(
    markdownStream: Stream<Char>,
    modifier: Modifier = Modifier,
    textColor: Color = LocalContentColor.current,
    fontSize: TextUnit = Unspecified,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    onLinkClick: ((String) -> Unit)? = null,
    xmlRenderer: XmlContentRenderer = remember { DefaultXmlRenderer() },
    nodeGrouper: MarkdownNodeGrouper = NoopMarkdownNodeGrouper,
    state: StreamMarkdownRendererState? = null,
    enableDialogs: Boolean = true,
    fillMaxWidth: Boolean = true,
)
```

出处：`StreamMarkdownRenderer.kt:380-393`。静态版本见 `StreamMarkdownRenderer.kt:874-887`。

耦合点逐条：

1. 渲染器不订阅事件通道。它通过类型转换读取替换流上的前缀：
   ```kotlin
   val rollbackPrefix = (markdownStream as? StreamRollbackPrefix)?.rollbackPrefix
   ```
   `StreamMarkdownRenderer.kt:427`；接口定义在 `RevisableTextStream.kt:19-22`。

2. 有前缀时，渲染器先完整解析前缀，再做节点级前缀比较，只丢弃不匹配的尾巴：
   `StreamMarkdownRenderer.kt:454-481`，比较算法在 `StreamMarkdownRenderer.kt:170-191`。

3. 前缀同时作为原生分块器的 `initialContent`，否则会从空状态重新分块：
   `StreamMarkdownRenderer.kt:486-490`；行内分块同理 `StreamMarkdownRenderer.kt:567-571`。

4. 回滚后如果当前块类型相同，会合并到上一个节点而不是新建块：
   `StreamMarkdownRenderer.kt:525-533`、`549`。

5. 字符流转换时显式继承事件通道与前缀：
   `MarkdownProcessor.kt:84-102`。如果丢掉这层，渲染器就看不到回滚。

6. 渲染器真正消费的是 `Stream<String>` 的块流，再在块内消费 `Stream<String>` 行内流，见 `StreamMarkdownRenderer.kt:483-643`。它完全不知道 Provider 是谁。

### 3.5 竞态与顺序假设

文本流和事件流是两条独立的 `SharedStream`，没有跨通道的顺序保证：

- Provider 侧顺序是"先改 `receivedContent`，再 `eventChannel.emit`"（`OpenAIProvider.kt:1581-1588`）。
- 消费侧 `RevisableTextStreamRemember` 用两个协程分别收集文本和事件（`RevisableTextStreamRemember.kt:41-84`），只靠 `stateMutex` 保护 append 与 rollback 的原子性，无法保证"事件到达时对应的文本片段已经 append 过"。
- `MessageProcessingDelegate` 同样是两个协程（`MessageProcessingDelegate.kt:1319-1351` 与 `1354`）。

在"回滚后整轮重发"的语义下，这个竞态通常是良性的，因为回滚目标是 0；一旦改成"局部回滚"，两条通道的顺序就会变成正确性前提。移植时不能默认它成立。

---

## 4. "回滚重发"链路全景

### 4.1 触发条件

Provider 捕获异常后调用 `handleRetryableError`，若判定可重试且还有额度，就执行 `onRetryAccepted`，而它被绑定为 `emitRollback(requestSavepointId)`：

- OpenAI：`OpenAIProvider.kt:3503-3515`，回调 `3511`。
- Claude：`ClaudeProvider.kt:2002-2013`，回调 `2009`。
- Gemini：`GeminiProvider.kt:1320-1331`，回调 `1327`。

重试策略本身在 `OpenAIProvider.kt:1640-1680`（Claude/Gemini 共用同名逻辑或等价实现）与 `LlmRetryPolicy.kt:3-12`：最多 5 次，指数退避 1s 到 16s。

### 4.2 OpenAIProvider 已有的例外：中断即正常

`OpenAIProvider.kt:3478-3501` 已经改成：

```kotlin
val partialLength = receivedContent.length
val isCancellation = e is CancellationException || e is UserCancellationException
if (partialLength > 0 && !isCancellation) {
    emitter.emitTag("\n\n")
    emitter.emitTag(context.getString(R.string.openai_stream_interrupted_kept))
    onUsageFinalized?.invoke(retryCount + 1)
    return@stream
}
```

也就是说：只要已经产出过正文，OpenAI 路径不会回滚，而是保留内容、追加一行中断提示、正常结束本轮。注释明确这是"移植太墟的会话语义"（`OpenAIProvider.kt:3479-3487`）。

Claude 与 Gemini 没有这个分支：它们在 `catch` 里直接进入 `handleRetryableError`（`ClaudeProvider.kt:1999-2013`、`GeminiProvider.kt:1318-1331`），因此仍会把已产出内容回滚掉再重发。这是当前实现里最明显的语义分裂。

### 4.3 参与文件与行号（按链路顺序）

Provider 产生侧：

| 文件 | 行号 | 作用 |
| --- | --- | --- |
| `llmprovider/OpenAIProvider.kt` | 1524-1601 | `StreamEmitter`：append / savepoint / rollback |
| `llmprovider/OpenAIProvider.kt` | 3230 | 创建 `eventChannel` |
| `llmprovider/OpenAIProvider.kt` | 3251-3254 | 创建 `receivedContent`，打请求起点 savepoint |
| `llmprovider/OpenAIProvider.kt` | 3478-3515 | 中断保留内容或回滚重试 |
| `llmprovider/OpenAIProvider.kt` | 3539 | `withEventChannel` 返回 |
| `llmprovider/ClaudeProvider.kt` | 1314-1335 | 事件通道、savepoint/rollback 本地函数 |
| `llmprovider/ClaudeProvider.kt` | 1401、2009、2032 | 打点、回滚回调、返回 |
| `llmprovider/GeminiProvider.kt` | 1179-1210 | 事件通道、savepoint/rollback 本地函数 |
| `llmprovider/GeminiProvider.kt` | 1222、1327、1345 | 打点、回滚回调、返回 |
| `llmprovider/LlmRetryPolicy.kt` | 3-12 | 重试次数与退避 |
| `llmprovider/OpenAIProvider.kt` | 1640-1680 | `handleRetryableError` |

装饰与转发侧：

| 文件 | 行号 | 作用 |
| --- | --- | --- |
| `llmprovider/TokenTrackingAIService.kt` | 173-183、211-230 | 保留 `eventChannel` 的包装 |
| `llmprovider/RateLimitedAIService.kt` | 14-121 | `AIService by delegate`，转发流与取消；**重建普通 stream，丢 eventChannel** |
| `api/chat/EnhancedAIService.kt` | 435、966 | 创建并保存 `eventChannel` |
| `api/chat/EnhancedAIService.kt` | 1146-1187 | 主聊天路径：转发事件、回滚 `streamBuffer`/`roundManager` |
| `api/chat/EnhancedAIService.kt` | 2404-2433 | 工具结果续轮路径：同样逻辑 |
| `api/chat/EnhancedAIService.kt` | 1336 | `withEventChannel` 返回 |
| `core/chat/AIMessageManager.kt` | 511-521 | `shareRevisable`，把事件通道复制成热流 |

内容与持久化侧：

| 文件 | 行号 | 作用 |
| --- | --- | --- |
| `util/stream/TextStreamRevisionTracker.kt` | 5-29 | 长度式 savepoint / truncate |
| `util/stream/RevisableTextStream.kt` | 5-28 | 事件与 carrier 类型 |
| `util/stream/RevisableTextStream.kt` | 125-177 | `withEventChannel`、`shareRevisable` |
| `services/core/MessageProcessingDelegate.kt` | 360-371 | 最终内容按事件通道选择来源 |
| `services/core/MessageProcessingDelegate.kt` | 1231-1232 | 主路径 tracker 与锁 |
| `services/core/MessageProcessingDelegate.kt` | 1319-1351 | 主路径事件收集与回滚 |
| `services/core/MessageProcessingDelegate.kt` | 1354-1390 | 文本 append、快照持久化、收尾写回 |
| `services/core/MessageProcessingDelegate.kt` | 1738-1779 | 重新生成路径的 tracker、事件、最终内容 |
| `services/core/MessageProcessingDelegate.kt` | 496-566 | 取消时保留/丢弃部分内容 |

UI 显示侧：

| 文件 | 行号 | 作用 |
| --- | --- | --- |
| `ui/features/chat/components/RevisableTextStreamRemember.kt` | 22-25 | `RollbackTailStream` |
| `ui/features/chat/components/RevisableTextStreamRemember.kt` | 36-71 | 事件收集、替换流、前缀 |
| `ui/features/chat/components/RevisableTextStreamRemember.kt` | 77-89 | 文本 append 到当前流 |
| `util/markdown/MarkdownProcessor.kt` | 84-102 | `toCharStream` 继承事件与前缀 |
| `ui/common/markdown/StreamMarkdownRenderer.kt` | 170-191 | 回滚尾部节点替换 |
| `ui/common/markdown/StreamMarkdownRenderer.kt` | 427-481 | 前缀解析与初始状态 |
| `ui/common/markdown/StreamMarkdownRenderer.kt` | 486-490、525-533、549、567-571 | 原生分块初始内容与合并策略 |

辅助消费者：

| 文件 | 行号 | 作用 |
| --- | --- | --- |
| `util/WaifuMessageProcessor.kt` | 194-240 | Waifu 分段后再发 |
| `util/WaifuMessageProcessor.kt` | 242-280 | TTS 文本化，丢弃 XML |
| `integrations/externalchat/ExternalChatResponseSanitizer.kt` | 29-41 | 外部输出剥 XML |
| `services/core/MessageProcessingDelegate.kt` | 1237-1303 | 自动朗读与 Waifu 旁路订阅 |

### 4.4 这条链路的成本

- 每个 Provider 都要维护一份 `receivedContent`，每个上层再维护一份，文本被复制至少四次。
- `replay = Int.MAX_VALUE` 的热流让整轮文本常驻（`OpenAIProvider.kt:3230`、`EnhancedAIService.kt:966`、`AIMessageManager.kt:512-513`）。
- 回滚是"整轮重来"，用户会看到内容消失再重打；OpenAI 路径已经修掉，Claude/Gemini 路径仍在。
- 事件通道与文本通道无顺序保证，只支持"回到 0"这种粗粒度回滚。

---

## 5. 可原样保留、必须替换、可改造

### 5.1 可原样保留

| 模块 | 文件 | 保留条件 |
| --- | --- | --- |
| Markdown 节点模型与解析 | `util/markdown/MarkdownProcessor.kt`、`util/markdown/SmartString.kt` | 提供 `Stream<Char>` 或适配层 |
| 流式 Markdown 插件 | `util/stream/plugins/StreamMarkdownPlugin.kt`（1509 行）、`StreamXmlPlugin.kt`、`StreamJsonPlugin.kt` | 保留 `Stream` 算子 |
| 原生分块器 | `util/streamnative/NativeMarkdownStreamOperators.kt:419-503`、`NativeMarkdownSplitter.kt:44-50` | 保留 JNI `libstreamnative`，见 `app/src/main/cpp/CMakeLists.txt:76-88` |
| Markdown Canvas 渲染 | `ui/common/markdown/CanvasMarkdownNodeRenderer.kt:416`、`MarkdownNodeGrouper.kt:20-40` | 不改渲染输入 |
| XML 渲染 | `ui/common/markdown/StreamMarkdownRenderer.kt:242-316`（接口与默认实现）、`ui/features/chat/components/part/CustomXmlRenderer.kt:69-184` | 只要输出仍是 XML 文本 |
| 工具/思考 XML 分组 | `ui/features/chat/components/part/ThinkToolsXmlNodeGrouper.kt:51`、`:227`、`:405` | 同上 |
| 标记解析 | `util/ChatMarkupRegex.kt:3-95` | 只要 Provider 仍产出同类标记 |
| 轮内容管理 | `api/chat/enhance/ConversationRoundManager.kt:13-101` | 与传输解耦 |
| 输入层 Provider 适配 | 各 `*Provider.kt` 的 `createRequestBody`、SSE 解析、多模态处理 | 只要 `AIService.sendMessage` 参数不变 |
| Token 统计 | `TokenTrackingAIService.kt`、`TokenCacheManager` | 只要流仍是逐段文本 |
| 聊天存储 | `ChatMessage`、Repository、Room | 保留 `content` 字段；只改流字段 |

### 5.2 必须替换

| 对象 | 位置 | 原因 |
| --- | --- | --- |
| `TextStreamEvent` / `TextStreamEventType` | `RevisableTextStream.kt:5-13` | 太墟以消息状态与轮结果为中心，不需要"回滚重放"补丁 |
| `TextStreamRevisionTracker` 及四份复制 | `TextStreamRevisionTracker.kt:5-29` 等 | 同上 |
| Provider 内回滚重发 | `OpenAIProvider.kt:1574-1588`、`ClaudeProvider.kt:1330-1335`、`GeminiProvider.kt:1205-1210` | 与"中断保留内容"的新语义冲突 |
| `StreamRollbackPrefix` 渲染分支 | `StreamMarkdownRenderer.kt:427-481`、`RevisableTextStream.kt:19-22` | 没有回滚就没有前缀替换 |
| `rememberRevisableTextStream` | `RevisableTextStreamRemember.kt:27-95` | 换成消息状态投影后不再需要替换流 |
| `ChatMessage.contentStream: Stream<String>?` | `ChatMessage.kt:31` | 类型契约要随输出层改 |
| 以 XML 文本为外部协议的部分 | `ExternalChatResponseSanitizer.kt:29-41`、`WebChatHttpBridge.kt:1159-1196`、`A2aTaskManager.kt:139-147` | 若新层输出结构化事件，这些消费者要改协议；若仍输出 XML，可保留 |
| 以"整轮文本重拼"为核心的完成逻辑 | `EnhancedAIService.kt:1846-1870`、`MessageProcessingDelegate.kt:360-371` | 需要按轮结果/消息状态重写 |

### 5.3 可改造而非重写

- `AIService.sendMessage` 本身可以保留参数，只改返回类型；或者新增 `sendMessageEvents` 并保留旧方法做兼容适配。
- `EnhancedAIService` 的上下文预算、工具循环、记忆更新可以保留，只需把"往流里 emit 文本"改成"发布消息状态/事件"。
- `MessageProcessingDelegate` 的持久化节流、自动朗读、Waifu 分段可以保留，只需把输入从"文本增量"改成"消息快照 + 增量"。
- `StreamMarkdownRenderer` 可以完全不动，用一个 `Flow/StateFlow -> Stream<Char>` 适配器喂它；这是最小改动路径。

### 5.4 与太墟的边界提示

太墟侧的对应物是"轮结果 + 消息状态"，不是文本流：

- `ChatResult` 聚合一轮的 `content`、`toolCalls`、`reasoningContent`、`usage`、`responsesTurn`，见 `/storage/emulated/0/inmyfeelings/inmyfeelings/taixu-main/harness/src/main/java/top/wkbin/taixu/harness/ChatResult.kt:3-17`。
- UI 侧观察的是 `StateFlow<List<HarnessMessage>>` 与每会话状态，见 `HarnessLoop.kt:134-142`；助手消息是 `AssistantText`，正文用 `ChunkedText`，见 `HarnessMessage.kt:123-128`。

因此"移植"的实质是把 Operit 的"文本流 + 回滚事件"换成"消息状态 + 轮结果"，再由适配层继续喂现有渲染器。详细映射应由 03 文档完成；本文只固定 Operit 侧的接缝事实。

---

## 6. 建议的最小切口

按侵入面从小到大：

1. 在 `AIService` 旁新增结构化输出通道，例如 `Flow<ModelEvent>`，其中至少区分 `TextDelta`、`ReasoningDelta`、`ToolCall`、`ToolResult`、`Usage`、`TurnEnd(reason)`。
2. 保留 `sendMessage(...): Stream<String>` 作为兼容层，由新通道适配生成 XML 文本，旧渲染器与外部 SSE 不动。
3. 新聊天链路直接消费结构化通道，逐步替换 `MessageProcessingDelegate` 与 `EnhancedAIService` 的文本重拼逻辑。
4. 删除 `SAVEPOINT/ROLLBACK` 与四份 tracker；中断改为"保留已产出 + 明确状态"，与 `OpenAIProvider.kt:3478-3501` 对齐。
5. 最后替换 `ChatMessage.contentStream` 与 `rememberRevisableTextStream`，让 UI 直接读消息状态；渲染器仍可用适配器接收 `Stream<Char>`。

验收时要特别看三件事：

- 同一轮里工具调用与正文交错时，UI 顺序是否仍与最终 `content` 一致。
- 取消/断网后，已产出内容是否保留且不重复。
- 外部 SSE/A2A 的输出是否仍满足 `ExternalChatResponseSanitizer` 的输入假设。

---

## 7. 附录：一页速查

```
输入层
  AIService.kt:60-74                  sendMessage 契约
  EnhancedAIService.kt:906-908        业务门面
  AIServiceFactory.kt:269-280         装配
  MultiServiceManager.kt:316-353      限流装饰

输出层
  OpenAIProvider.kt:3215-3230         Provider 入口
  OpenAIProvider.kt:1524-1601         StreamEmitter
  EnhancedAIService.kt:966-967        再包一层
  EnhancedAIService.kt:1336           withEventChannel
  AIMessageManager.kt:511-521         shareRevisable
  MessageProcessingDelegate.kt:1231   主消费
  ChatMessage.kt:31                   流字段
  RevisableTextStreamRemember.kt:27   UI 适配
  StreamMarkdownRenderer.kt:380-393   渲染入口

断点
  RateLimitedAIService.kt:31-121      丢 eventChannel

回滚
  RevisableTextStream.kt:5-28         事件类型
  TextStreamRevisionTracker.kt:5-29   长度式 tracker
  OpenAIProvider.kt:1574-1588         savepoint/rollback
  OpenAIProvider.kt:3478-3515         中断保留 vs 回滚
  ClaudeProvider.kt:1999-2013         仍回滚
  GeminiProvider.kt:1318-1331         仍回滚
  StreamMarkdownRenderer.kt:170-191   尾部替换
```
