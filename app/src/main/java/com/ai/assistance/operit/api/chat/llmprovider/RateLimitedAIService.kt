package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.util.stream.MutableSharedStream
import com.ai.assistance.operit.util.stream.RevisableTextStream
import com.ai.assistance.operit.util.stream.SharedStream
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.StreamCollector
import com.ai.assistance.operit.util.stream.TextStreamEvent
import com.ai.assistance.operit.util.stream.TextStreamEventCarrier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore

/**
 * 限流 / 并发闸门。
 *
 * 这里有一个踩过的坑，改动前务必先读：
 *
 * 旧实现把下游流包在自己的 stream 构建器里，只用 collect 转发字符串。
 * 但 SAVEPOINT / ROLLBACK 这类修订事件走的是 Stream 的旁路事件通道（eventChannel），
 * 不是字符串，于是被整层吞掉。后果：
 *   - Provider 内部回滚了、清空了自己的缓冲、从头重发；
 *   - UI 侧永远收不到 ROLLBACK，旧文本不清空；
 *   - 用户看到的是内容重复或错乱，体感就是"输出中断又重连"。
 *
 * 只要用户开了限流或并发限制，这条路径就是默认路径。
 *
 * 现在的做法：闸门（排队、拿并发令牌）在 sendMessage 里同步完成，
 * 返回的流原样转发下游的 collect 与 eventChannel，只在结束或异常时归还令牌。
 */
class RateLimitedAIService(
    private val delegate: AIService,
    private val rateLimiter: SlidingWindowRateLimiter?,
    private val concurrencySemaphore: Semaphore?
) : AIService by delegate {
    private val queuedRequests = ConcurrentHashMap.newKeySet<AtomicBoolean>()
    private val cancellationLock = Any()
    private var cancellationEpoch = 0L

    override fun cancelStreaming() {
        synchronized(cancellationLock) {
            cancellationEpoch += 1
            queuedRequests.forEach { request -> request.set(true) }
        }
        delegate.cancelStreaming()
    }

    override suspend fun sendMessage(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean,
        onTokensUpdated: suspend (input: Long, cachedInput: Long, output: Long) -> Unit,
        onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, attempt: Int) -> Unit)?,
        onNonFatalError: suspend (error: String) -> Unit,
        enableRetry: Boolean,
        recordTokenUsage: Boolean,
        onUsageFinalized: (suspend (attempt: Int?) -> Unit)?,
    ): Stream<String> {
        val requestEpoch = synchronized(cancellationLock) { cancellationEpoch }
        val cancelled = AtomicBoolean(false)
        synchronized(cancellationLock) {
            // 冷流可能在开始收集前就被取消，这里原子地登记并比对 epoch
            if (cancellationEpoch != requestEpoch) {
                cancelled.set(true)
            }
            queuedRequests.add(cancelled)
        }

        fun throwIfCancelled() {
            if (cancelled.get()) throw CancellationException("AI request was cancelled")
        }

        var acquired = false
        try {
            throwIfCancelled()

            val limiter = rateLimiter
            if (limiter != null) {
                while (true) {
                    throwIfCancelled()
                    val retryAfterMs = limiter.tryAcquire()
                    if (retryAfterMs <= 0L) break
                    delay(retryAfterMs.coerceAtMost(CANCELLATION_POLL_INTERVAL_MS))
                }
            }

            throwIfCancelled()

            val semaphore = concurrencySemaphore
            if (semaphore != null) {
                while (true) {
                    throwIfCancelled()
                    if (semaphore.tryAcquire()) break
                    delay(CANCELLATION_POLL_INTERVAL_MS)
                }
                acquired = true
            }

            throwIfCancelled()

            val downstream =
                delegate.sendMessage(
                    context = context,
                    chatHistory = chatHistory,
                    modelParameters = modelParameters,
                    enableThinking = enableThinking,
                    stream = stream,
                    availableTools = availableTools,
                    preserveThinkInHistory = preserveThinkInHistory,
                    onTokensUpdated = onTokensUpdated,
                    onUsageReported = onUsageReported,
                    onNonFatalError = onNonFatalError,
                    enableRetry = enableRetry,
                    recordTokenUsage = recordTokenUsage,
                    onUsageFinalized = onUsageFinalized,
                )

            return RelayingRevisableStream(
                upstream = downstream,
                cancelled = cancelled,
                onReleased = {
                    if (acquired) {
                        semaphore?.release()
                        acquired = false
                    }
                    synchronized(cancellationLock) { queuedRequests.remove(cancelled) }
                },
            )
        } catch (t: Throwable) {
            // 还没把流交出去就失败了：令牌与登记必须立刻归还
            if (acquired) concurrencySemaphore?.release()
            synchronized(cancellationLock) { queuedRequests.remove(cancelled) }
            throw t
        }
    }

    private companion object {
        const val CANCELLATION_POLL_INTERVAL_MS = 50L
    }
}

/**
 * 只做两件事的装饰器：
 * 1. collect 原样交给下游（字符串照旧流转）；
 * 2. 把下游的 eventChannel 原样暴露出去（这是旧实现丢掉的东西），
 *    并借 collect 的收尾时机归还并发令牌。
 */
private class RelayingRevisableStream(
    private val upstream: Stream<String>,
    private val cancelled: AtomicBoolean,
    private val onReleased: () -> Unit,
) : RevisableTextStream {

    override val eventChannel: SharedStream<TextStreamEvent> =
        (upstream as? TextStreamEventCarrier)?.eventChannel
            ?: MutableSharedStream(replay = Int.MAX_VALUE)

    override val isLocked: Boolean
        get() = upstream.isLocked

    override val bufferedCount: Int
        get() = upstream.bufferedCount

    override suspend fun lock() {
        upstream.lock()
    }

    override suspend fun unlock() {
        upstream.unlock()
    }

    override fun clearBuffer() {
        upstream.clearBuffer()
    }

    override suspend fun collect(collector: StreamCollector<String>) {
        try {
            upstream.collect(
                object : StreamCollector<String> {
                    override suspend fun emit(value: String) {
                        if (cancelled.get()) {
                            throw CancellationException("AI request was cancelled")
                        }
                        collector.emit(value)
                    }
                }
            )
        } finally {
            onReleased()
        }
    }
}
