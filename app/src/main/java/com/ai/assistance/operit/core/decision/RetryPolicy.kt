package com.ai.assistance.operit.core.decision

import kotlinx.coroutines.delay
import kotlin.math.pow

/**
 * 纯连接重试决策:指数退避 + 抖动。
 * 移植自 PalmClaw 的 McpConnectionRetryPolicy,抽掉对具体传输异常的依赖,
 * 改为通过 [isRetryable] 谓词注入「这个失败该不该重试」的判断。
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMillis: Long = 500L,
    val maxDelayMillis: Long = 4_000L,
    val jitterRatio: Double = 0.2,
    val isRetryable: (Throwable) -> Boolean = { true },
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least one" }
        require(baseDelayMillis >= 0L) { "baseDelayMillis must not be negative" }
        require(maxDelayMillis >= baseDelayMillis) { "maxDelayMillis must be at least baseDelayMillis" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be between zero and one" }
    }

    /**
     * 计算第 [failedAttempt] 次失败后的等待时长。
     * 返回 null 表示不该再重试(次数用尽或失败不可重试)。
     * [jitterUnit] 为 [0,1] 区间的随机值,通常传 Math.random()。
     */
    fun delayBeforeNextAttempt(
        failedAttempt: Int,
        failure: Throwable,
        jitterUnit: Double,
    ): Long? {
        if (failedAttempt >= maxAttempts || failedAttempt < 1) return null
        if (!isRetryable(failure)) return null
        val exponential = baseDelayMillis.toDouble() * 2.0.pow((failedAttempt - 1).toDouble())
        val capped = exponential.coerceAtMost(maxDelayMillis.toDouble())
        val positiveJitter = jitterUnit.coerceIn(0.0, 1.0) * jitterRatio
        return (capped * (1.0 + positiveJitter)).toLong()
            .coerceAtMost(maxDelayMillis)
            .coerceAtLeast(0L)
    }

    /** 挂起版:直接等待下一次重试前的时延;返回 false 表示不该再重试。 */
    suspend fun waitBeforeNextAttempt(
        failedAttempt: Int,
        failure: Throwable,
        jitterUnit: Double = Math.random(),
    ): Boolean {
        val delayMs = delayBeforeNextAttempt(failedAttempt, failure, jitterUnit) ?: return false
        if (delayMs > 0) delay(delayMs)
        return true
    }
}

/**
 * 针对 HTTP 连接失败的通用重试判定:
 * - 4xx 里 408/429 可重试,其余客户端错误重试无意义;
 * - 5xx 可重试(服务端临时故障);
 * - 网络层 IO/Socket 错误可重试。
 */
fun httpRetryable(failure: Throwable): Boolean {
    val status = (failure as? HttpStatusError)?.statusCode
    if (status != null) {
        return status == 408 || status == 429 || status in 500..599
    }
    val name = failure.javaClass.name
    return name.contains("ConnectException") ||
        name.contains("UnknownHostException") ||
        name.contains("SocketTimeoutException") ||
        name.contains("SocketException") ||
        name.contains("IOException")
}

/** 带 HTTP 状态码的异常包装,供 [httpRetryable] 识别。 */
class HttpStatusError(val statusCode: Int, message: String? = null, cause: Throwable? = null) :
    Exception(message, cause)
