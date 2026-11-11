package com.ai.assistance.operit.core.decision

import java.net.URL

/**
 * 端点源(origin)判定:scheme + host + port 三元组。
 * 移植自 PalmClaw 的 ProviderEndpointRetryPolicy 中「认证域隔离」的核心思想:
 *
 * 当 provider 重试要切换到另一个端点时,若两个端点属于不同 origin,
 * 那么当前端点上的 401/403(认证失败)绝不能带到下一个端点——
 * 因为下一个端点是另一个服务,用的是另一套凭据,盲目重试只会再次失败,
 * 甚至把 A 服务的凭据发给 B 服务造成泄露。
 *
 * Operit 目前是「单端点 + EndpointCompleter 补全」模式,尚无备用端点池,
 * 但此工具把「同源判定」这一基础能力独立出来,未来加备用端点时可直接复用。
 */
data class EndpointOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
) {
    companion object {
        /** 从 URL 字符串解析 origin;无法解析返回 null。 */
        fun parse(url: String): EndpointOrigin? = try {
            val parsed = URL(url.trim())
            EndpointOrigin(
                scheme = parsed.protocol.lowercase(),
                host = parsed.host.lowercase(),
                port = effectivePort(parsed),
            )
        } catch (_: Exception) {
            null
        }

        private fun effectivePort(url: URL): Int {
            val p = url.port
            if (p != -1) return p
            return when (url.protocol.lowercase()) {
                "https" -> 443
                "http" -> 80
                else -> -1
            }
        }
    }
}

/**
 * 认证域隔离判定:判断「从 current 重试到 next」是否安全。
 *
 * @param current 当前失败端点
 * @param next 要切换到的下一个端点
 * @param failure 导致重试的异常(需要从中提取 HTTP 状态码)
 * @return true 表示允许切换到 next,false 表示不应切换
 *
 * 规则:
 * - 同源:总是允许(同一服务重试,凭据一致);
 * - 跨源:仅当失败不是 401/403(认证错误)时才允许;
 *   认证错误跨源重试会把凭据发给不相关的服务,既不安全也无意义。
 */
fun allowsCredentialScope(
    current: String,
    next: String,
    failure: Throwable,
): Boolean {
    val currentOrigin = EndpointOrigin.parse(current) ?: return false
    val nextOrigin = EndpointOrigin.parse(next) ?: return false
    if (currentOrigin == nextOrigin) return true
    val statusCode = (failure as? HttpStatusError)?.statusCode
    return statusCode != 401 && statusCode != 403
}
