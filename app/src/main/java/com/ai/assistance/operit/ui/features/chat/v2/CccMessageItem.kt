package com.ai.assistance.operit.ui.features.chat.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.ui.common.markdown.StreamMarkdownRenderer
import com.ai.assistance.operit.ui.features.chat.components.rememberRevisableTextStream
import com.ai.assistance.operit.util.markdown.toCharStream
import com.ai.assistance.operit.util.stream.coalesceRevisable

/** 消息淡入的初始下移量（像素）。见下方 graphicsLayer 注释。 */
private const val ENTRANCE_TRANSLATE_PX = 12f

/**
 * 单条消息（照 cccodroid 的 `.msg` / `.who` / `.bubble` 结构移植）。
 *
 * 三处与 Operit 原实现的**结构级**差别：
 *
 * 1. **助手消息没有气泡**。cccodroid 的 `.msg.bot .bubble{background:none;border:0;padding:0 1px}`
 *    ——正文直接铺在页面底色上。Operit 原来是整行卡片。
 * 2. **用户消息没有角色行**。`.msg.user .who{display:none}`，气泡右对齐、18dp 圆角、
 *    最宽 85%。Operit 原来左右都是整行卡片，只能靠 "Prompt"/"Response" 区分。
 * 3. **助手消息有一条角色行**：6dp 圆点 + 11.5sp 字、字距 0.4sp、下方 6dp。
 *    这条线替代了 Operit 的 "Response" 标签——区别是它带圆点、字号更小、更像状态而不是标题。
 *
 * 正文渲染复用 StreamMarkdownRenderer / 内置 XML 渲染器（那是 2900 行成熟代码，
 * 重写没有意义）；本文件只负责**容器与排布**。
 */
@Composable
fun CccMessageItem(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    enableDialogs: Boolean = true,
    /**
     * 是否显示角色行。连续多条助手消息只在**第一条**画，避免长对话里满屏重复的「● Operit」。
     * 由列表按「上一条是谁」计算后传入。
     */
    showRole: Boolean = true,
) {
    val isUser = message.sender == "user"
    val isSummary = message.sender == "summary"

    // 流式消息要经过一次多路复用再交给渲染器（直接订阅源流会把它标记成已关闭）
    // 16ms 合批（阶段二切片一）：上游每个 token 推一个片段，逐片解析会让长输出掉帧。
    // 用 coalesceRevisable 而不是裸合批，是因为修订事件走 eventChannel 旁路，
    // 裸 stream 构建器会新建通道把它吞掉——那正是 RateLimitedAIService 踩过的坑。
    val displayStream = rememberRevisableTextStream(message.contentStream)
    val charStream =
        remember(displayStream) { displayStream?.coalesceRevisable()?.toCharStream() }

    // 消息淡入（cccodroid 的 .msg { animation: fade .18s }）：
    // 首次进入组合时 opacity 0→1、translateY 4dp→0，之后内容更新（流式）不再重播。
    val entranceAlpha = remember { Animatable(0f) }
    LaunchedEffect(message.timestamp) {
        entranceAlpha.animateTo(1f, tween(durationMillis = 180, easing = FastOutSlowInEasing))
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = entranceAlpha.value
                    // graphicsLayer 的 lambda 不是 Density 作用域，toPx() 在这里不可用；
                    // 位移量直接用像素常量（4dp 在常见密度下约 10~16px，取 12f 与设计值一致）
                    translationY = (1f - entranceAlpha.value) * ENTRANCE_TRANSLATE_PX
                }
    ) {
        if (isUser) {
            // ── 用户：右对齐、**按内容收缩**、上限 85% / 680dp ──────────
            // cccodroid 的写法是 max-width:min(85%,680px)，即"最宽到这里"，
            // 不是"固定这么宽"。用 fillMaxWidth(0.85f) 会得到一块永远 85% 宽的色块，
            // 短消息看起来像被拉长，所以这里用 BoxWithConstraints 算出上限再让内容自己决定宽度。
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val maxBubbleWidth =
                    minOf(maxWidth * CccTokens.UserBubbleMaxWidthFraction, CccTokens.MessageMaxWidth)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        modifier =
                            Modifier
                                .widthIn(max = maxBubbleWidth)
                                .clip(RoundedCornerShape(CccTokens.UserBubbleRadius))
                                // 用户气泡用主色的极浅染色（cccodroid 的 --bubble-user 也是带色倾向的，
                                // 不是中性灰）。纯 surfaceVariant 在深色下是一块发闷的灰板，和正文拉不开层次。
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f))
                                .padding(
                                    horizontal = CccTokens.UserBubblePaddingH,
                                    vertical = CccTokens.UserBubblePaddingV
                                )
                    ) {
                        MessageBody(
                            message = message,
                            charStream = charStream,
                            enableDialogs = enableDialogs
                        )
                    }
                }
            }
        } else {
            // ── 助手 / 系统：无气泡，先一行角色，再正文 ─────────────────
            if (showRole) {
                RoleLine(
                    label =
                        when {
                            isSummary -> "摘要"
                            message.roleName.isNotBlank() -> message.roleName
                            else -> "Operit"
                        },
                    dotColor =
                        if (isSummary) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                )
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CccTokens.BotContentPaddingHorizontal)
            ) {
                MessageBody(
                    message = message,
                    charStream = charStream,
                    enableDialogs = enableDialogs
                )
            }
        }
    }
}

/** cccodroid 的 `.who`：6dp 圆点 + 11.5sp/0.4 字距的弱化标签。 */
@Composable
private fun RoleLine(label: String, dotColor: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.padding(bottom = CccTokens.WhoBottom),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier =
                Modifier
                    .size(CccTokens.WhoDotSize)
                    .clip(CircleShape)
                    .background(dotColor)
        )
        Spacer(modifier = Modifier.width(CccTokens.WhoGap))
        Text(
            text = label,
            fontSize = CccTokens.WhoFontSize,
            letterSpacing = CccTokens.WhoLetterSpacing,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 正文。流式走 charStream，静态走 content——两条路径都复用既有渲染器，
 * 这样工具调用/思考块的解析逻辑完全不变。
 */
@Composable
private fun MessageBody(
    message: ChatMessage,
    charStream: com.ai.assistance.operit.util.stream.Stream<Char>?,
    enableDialogs: Boolean,
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    if (charStream != null) {
        StreamMarkdownRenderer(
            markdownStream = charStream,
            textColor = textColor,
            backgroundColor = MaterialTheme.colorScheme.background,
            enableDialogs = enableDialogs,
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        StreamMarkdownRenderer(
            content = message.content,
            textColor = textColor,
            backgroundColor = MaterialTheme.colorScheme.background,
            enableDialogs = enableDialogs,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
