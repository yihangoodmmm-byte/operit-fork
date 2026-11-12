package com.ai.assistance.operit.ui.features.chat.v2

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * cccodroid 的布局令牌（逐条抄自 gateway/web/style.css，不是估算值）。
 *
 * 出处对照：
 * - `#chat{padding:14px 12px 8px}`                     → [ListPaddingTop] 等
 * - `.msg{max-width:900px;margin:0 auto 16px}`          → [MessageGap]
 * - `.who{font-size:11.5px;gap:7px;margin-bottom:6px;...}` → [WhoFontSize] 等
 * - `.who .dot{width:6px;height:6px;border-radius:50%}`  → [WhoDotSize]
 * - `.msg.user .bubble{border-radius:18px;padding:9px 14px;max-width:min(85%,680px)}`
 * - `.bubble{padding:11px 13px;border-radius:14px}`
 *
 * 这些数字是这次重写的核心：Operit 原来的间距是 16/12/8 混着手写，
 * 没有统一节奏，所以看起来"能用但不好看"。
 */
internal object CccTokens {
    // 列表外框（iOS 呼吸感：左右 16dp、首尾更松）
    val ListPaddingTop = 16.dp
    val ListPaddingHorizontal = 16.dp
    val ListPaddingBottom = 12.dp

    /** 消息之间的间距（iOS 文档流更松，20dp）。 */
    val MessageGap = 20.dp

    /** 单条消息最大宽度上限（cccodroid 的 max-width:900px，手机上是满宽）。 */
    val MessageMaxWidth = 680.dp

    // 角色行（assistant 才有；user 隐藏）
    val WhoFontSize = 11.5.sp
    val WhoGap = 7.dp
    val WhoBottom = 6.dp
    val WhoLetterSpacing = 0.4.sp
    val WhoDotSize = 6.dp

    // 用户气泡
    val UserBubbleRadius = 18.dp
    val UserBubblePaddingH = 14.dp
    val UserBubblePaddingV = 9.dp
    /** `max-width:min(85%,680px)` */
    val UserBubbleMaxWidthFraction = 0.85f

    // 助手内容（无气泡）
    val BotContentPaddingHorizontal = 1.dp

    // 普通气泡（系统消息等）
    val BubbleRadius = 14.dp
    val BubblePaddingH = 13.dp
    val BubblePaddingV = 11.dp

    // 段落节奏
    val ParagraphBottom = 10.dp
}

/** token 数紧凑显示（cccodroid 的 fmtChars/usNum 同款）：12345 -> "12.3k"。 */
fun formatTokenCount(value: Long): String =
    when {
        value >= 1_000_000L -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000L -> String.format(java.util.Locale.US, "%.1fK", value / 1_000.0)
        else -> value.toString()
    }
