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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.ui.common.markdown.StreamMarkdownRenderer
import com.ai.assistance.operit.ui.features.chat.components.rememberRevisableTextStream
import com.ai.assistance.operit.util.markdown.toCharStream

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
) {
    val isUser = message.sender == "user"
    val isSummary = message.sender == "summary"

    // 流式消息要经过一次多路复用再交给渲染器（直接订阅源流会把它标记成已关闭）
    val displayStream = rememberRevisableTextStream(message.contentStream)
    val charStream = remember(displayStream) { displayStream?.toCharStream() }

    Column(modifier = modifier.fillMaxWidth()) {
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
                                .background(MaterialTheme.colorScheme.surfaceVariant)
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
