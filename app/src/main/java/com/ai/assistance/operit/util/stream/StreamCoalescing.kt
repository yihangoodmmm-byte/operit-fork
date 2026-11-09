package com.ai.assistance.operit.util.stream

/**
 * 把高频的小片段合并成至多每 [intervalMs] 一次的大片段。
 *
 * 背景（阶段二的第一个切片）：
 * 流式输出时上游每个 token 都会推一个 fragment，渲染端如果逐片解析，长输出会做几百次
 * 全量 Markdown 解析，表现为掉帧、滚动发抖、光标闪烁。太墟的做法是用一个 16ms 的
 * StreamBuffer 把片段合批后再交给 UI（约一帧一次）。
 *
 * 这里只做合批，不改语义：
 * - 片段顺序完全不变，拼起来等于原串；
 * - 流结束时把残留一次吐出，不会丢尾巴；
 * - 上游抛出时先吐已累积的尾巴再原样抛出，合批不吞异常。
 */
fun Stream<String>.coalesceText(intervalMs: Long = 16L): Stream<String> =
    CoalescingStream(this, intervalMs)

/**
 * 带修订事件通道的合批。
 *
 * 为什么单独一个重载：SAVEPOINT / ROLLBACK 走的是 Stream 的 eventChannel 旁路。
 * 合批层绝不能通过 stream 构建器新建一个冷流再重放上游——那样正文被延后 16ms
 * 而修订事件不延后，两者时序错位，回滚快照与屏幕内容对不上（正是
 * RateLimitedAIService 曾经踩过、后来修掉的那类「事件通道丢失」的变体）。
 * 这里直接包装上游的 collect，正文与事件仍在同一个消费时序里。
 */
fun Stream<String>.coalesceRevisable(intervalMs: Long = 16L): Stream<String> {
    val source = this
    val carrier = source as? TextStreamEventCarrier
    val base: Stream<String> = CoalescingStream(source, intervalMs)
    return if (carrier == null) base
    else CoalescedRevisableStream(base, carrier.eventChannel)
}

/**
 * 对上游 collect 做合批，不新建事件通道、不改订阅语义。
 *
 * 与 stream { } 构建器的关键区别：stream { } 会新建一个冷流，把上游 collect 一遍
 * 再 emit 出来。这带来两个问题：
 * 1. 修订事件（eventChannel 旁路）与正文不再同步，回滚时快照错位；
 * 2. 冷流每次 collect 都重新求值，多订阅时计时器互相干扰。
 * 这里直接包装上游的 collect，body 与事件仍在同一个上游消费时序里。
 */
private class CoalescingStream(
    private val upstream: Stream<String>,
    private val intervalMs: Long,
) : Stream<String> {
    override val isLocked: Boolean get() = upstream.isLocked
    override val bufferedCount: Int get() = upstream.bufferedCount
    override suspend fun lock() = upstream.lock()
    override suspend fun unlock() = upstream.unlock()
    override fun clearBuffer() = upstream.clearBuffer()

    override suspend fun collect(collector: StreamCollector<String>) {
        val pending = StringBuilder()
        var lastFlushAtMs = 0L

        suspend fun flush(force: Boolean) {
            if (pending.isEmpty()) return
            val now = System.nanoTime() / 1_000_000L
            if (!force && now - lastFlushAtMs < intervalMs) return
            lastFlushAtMs = now
            val merged = pending.toString()
            pending.setLength(0)
            collector.emit(merged)
        }

        try {
            upstream.collect { fragment ->
                pending.append(fragment)
                flush(force = false)
            }
            flush(force = true)
        } catch (t: Throwable) {
            // 先把已累积的尾巴吐出去，再原样抛出，不吞异常
            flush(force = true)
            throw t
        }
    }
}

private class CoalescedRevisableStream(
    private val upstream: Stream<String>,
    override val eventChannel: SharedStream<TextStreamEvent>,
) : RevisableTextStream {
    override val isLocked: Boolean get() = upstream.isLocked
    override val bufferedCount: Int get() = upstream.bufferedCount
    override suspend fun lock() = upstream.lock()
    override suspend fun unlock() = upstream.unlock()
    override fun clearBuffer() = upstream.clearBuffer()
    override suspend fun collect(collector: StreamCollector<String>) {
        upstream.collect(collector)
    }
}
