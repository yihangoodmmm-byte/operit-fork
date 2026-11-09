# 太墟输出层移植 · 源码现状

> 本文件记录**代码实际状态**，不是计划。计划见 03-mapping.md，分析见 01/02，许可见 04。
> 每次改动后应更新本文件的「已验证 / 未验证 / 待决策」三节。

最后更新：commit `ebfe7e2` + 未提交工作区改动

---

## 一、基线

| 项 | 值 |
| :--- | :--- |
| 分支 | `persona` |
| 已推送 HEAD | `ebfe7e2` fix(gemini): 中断标记改用 streamCollector.emit |
| 最近一次成功构建 | CI run 59（`ebfe7e2`），success |
| 装机产物 | `dist/inmyfeelings-code-1.0-stage1.apk`，sha256 `2627b011cb45e3e909d27d45f5b62cf9c2f9c4a5e80604ba0cf69abf82de7c62` |
| 许可 | 维持 **LGPL-3.0**（路线 b：只搬设计，不复制太墟代码） |

---

## 二、已验证（构建 + 装机）

### 2.1 限流包装吞噬修订事件（已修，已验证）

- **文件**：`app/src/main/java/com/ai/assistance/operit/api/chat/llmprovider/RateLimitedAIService.kt`
- **旧行为**：`delegate.sendMessage(...).collect { emit(it) }` 只转发字符串，
  `SAVEPOINT`/`ROLLBACK` 走 `eventChannel` 旁路被整层吞掉。
- **后果**：Provider 内部回滚重发、UI 收不到 ROLLBACK、旧文本不清空 → 内容重复/错乱。
- **新行为**：闸门（排队 + 并发令牌）前置到 `sendMessage` 内完成；
  返回 `RelayingRevisableStream`，原样转发下游 `collect` 与 `eventChannel`，
  仅在结束/异常时归还令牌。

### 2.2 三家 Provider 中断语义统一（已修，已验证）

| Provider | 文件 | 行为 |
| :--- | :--- | :--- |
| OpenAI | `OpenAIProvider.kt` | 已产出内容 + 非取消 → 保留内容、补标记、正常结束 |
| Claude | `ClaudeProvider.kt` | 同上（本次对齐，原为 `setLength(0)` 清空重发） |
| Gemini | `GeminiProvider.kt` | 同上；标记用 `streamCollector.emit`（跟随该文件既有约定） |

标记文案：`R.string.openai_stream_interrupted_kept` = 〔连接中断，以上内容已保留〕
无产出时（429/连不上）仍走原重试逻辑。

---

## 三、已实现但未验证（工作区，未提交、未构建）

### 3.1 16ms 合批（阶段二切片一）

- **新增**：`app/src/main/java/com/ai/assistance/operit/util/stream/StreamCoalescing.kt`
  - `Stream<String>.coalesceText(intervalMs = 16L)`：按帧合批，顺序不变、结束冲刷尾巴、异常不吞。
  - `Stream<String>.coalesceRevisable(intervalMs = 16L)`：上游是 `TextStreamEventCarrier` 时
    用 `CoalescedRevisableStream` 带出**同一个** eventChannel，避免合批层重演 2.1 的吞事件问题。
- **修改**：`app/src/main/java/com/ai/assistance/operit/ui/features/chat/v2/CccMessageItem.kt`
  - `displayStream?.toCharStream()` → `displayStream?.coalesceRevisable()?.toCharStream()`
- **目的**：上游每 token 推一个片段，逐片解析会让长输出掉帧、滚动发抖；
  对齐太墟 16ms StreamBuffer 的节奏。
- **状态**：⚠️ **未通过 CI 验证**。本地配对编译只证明 `Syntax error = 0`；
  隔离编译无法解析 `stream`/`RevisableTextStream` 等工程符号，报出的
  `suspend function flush can only be called...`、`xxx overrides nothing`
  属于级联噪音，**不能据此断定可编译**。

---

## 四、未开始

| 阶段 | 内容 | 阻塞点 |
| :--- | :--- | :--- |
| 阶段二主体 | UI 消费端改「同 id 全量快照替换」，删除 `SAVEPOINT`/`ROLLBACK` 全套 | 待决策 ②③ |
| 阶段三 | 输入层结构化 `ModelRequest`/`ContentPart` | 待决策 ①④ |

---

## 五、待决策（阻塞后续阶段）

1. **Room 21→22**：阶段三要给消息加结构化列。`MessageEntity` 与 `MessageVariantEntity`
   双写 `content`，两表必须同步；`exportSchema=false` 需手写 `MIGRATION_21_22`；
   **升级后旧安装包无法覆盖安装**。
2. **对外 XML 协议**：工具调用目前拍平为内嵌 XML 文本，外部 SSE/A2A 集成依赖它。
   改结构化后保留还是废弃？
3. **阶段二删除节奏**：删 `contentStream` 必须与「取消落库路径」同提交，否则取消会丢已产出内容。
4. **本地 Provider**（MNN / OpenCode）是否纳入替换范围。

---

## 六、受影响文件清单

### 已提交（ebfe7e2 及之前）

| 文件 | 性质 |
| :--- | :--- |
| `api/chat/llmprovider/RateLimitedAIService.kt` | 重写（保留通道） |
| `api/chat/llmprovider/OpenAIProvider.kt` | 中断语义 |
| `api/chat/llmprovider/ClaudeProvider.kt` | 中断语义 |
| `api/chat/llmprovider/GeminiProvider.kt` | 中断语义 |
| `app/src/main/res/values/strings.xml` | 新增中断标记文案 |

### 未提交（工作区）

| 文件 | 性质 |
| :--- | :--- |
| `util/stream/StreamCoalescing.kt` | 新增 |
| `ui/features/chat/v2/CccMessageItem.kt` | 接线 |

---

## 七、下一步验证方法

1. 推送 `ebfe7e2` 之后的工作区改动，跑 CI；**先确认 3.1 能编译**，再谈阶段二主体。
2. 装机后让用户正常使用，观察两点：
   - 中断时内容是否保留（应出现〔连接中断，以上内容已保留〕）；
   - **内容重复/错乱是否消失**——这一条决定 2.1 是否为主因，也决定阶段二是否按原计划走。
3. 若重复仍在，优先查还有哪些装饰器/包装层用 `stream {}` 重建了流而丢掉 eventChannel：

```bash
grep -rn "collect { .*emit(" app/src/main/java/com/ai/assistance/operit/api/chat/
```
