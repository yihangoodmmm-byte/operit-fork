package com.ai.assistance.operit.ui.features.chat.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 输入区（照 cccodroid 的 `#composer` / `#composer-inner` / `#hint-row` 移植）。
 *
 * cccodroid 的骨架：
 * ```
 * #composer                 ← 底部容器，padding 8/10 + 安全区
 *   #composer-inner         ← 一个圆角框，内部横向排列按钮与输入框（flex, align-items:flex-end）
 *     [+ 附件] [textarea] [发送]
 *   #hint-row               ← 框外下方一行居中细胶囊：模型 / 权限 / 用量
 * ```
 *
 * 与 Operit 原实现的结构差别：
 * - **只有一框**。Operit 是「模型胶囊行 + 工具图标行 + 附件行 + 输入框」多层堆叠。
 * - **按钮在框内**。cccodroid 把附件与发送都放进同一个圆角框里。
 * - **状态在框外**。模型/用量作为细胶囊排在框下面一行，不占框内空间。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CccComposer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
    isProcessing: Boolean,
    onStop: () -> Unit,
    placeholder: String,
    modelLabel: String,
    contextPercent: Int,
    contextText: String,
    onModelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val fieldShape = RoundedCornerShape(22.dp)

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(cs.background)
                .padding(
                    start = 10.dp,
                    end = 10.dp,
                    top = 8.dp,
                    bottom = 8.dp
                )
    ) {
        // ── 输入框本体：所有按钮与文本框在同一行 ──────────────────
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(fieldShape)
                    .background(cs.surface)
                    .border(1.dp, cs.outlineVariant, fieldShape)
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Box(
                modifier = Modifier.weight(1f).heightIn(min = 34.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = 15.5.sp,
                        color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle =
                        LocalTextStyle.current.copy(
                            color = cs.onSurface,
                            fontSize = 15.5.sp,
                            lineHeight = 22.sp
                        ),
                    cursorBrush = SolidColor(cs.primary),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 发送 / 停止：cccodroid 是 32dp 圆形，底色为强调色 18% 透明
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (isProcessing) cs.error
                            else if (canSend) cs.primary.copy(alpha = 0.18f)
                            else cs.surfaceVariant
                        )
                        .clickable(enabled = canSend || isProcessing) {
                            if (isProcessing) onStop() else onSend()
                        },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isProcessing) Icons.Default.Close else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (isProcessing) "停止" else "发送",
                    tint =
                        if (isProcessing) cs.onError
                        else if (canSend) cs.primary
                        else cs.onSurfaceVariant.copy(alpha = 0.38f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // ── 框外状态行：模型 / 上下文 ──────────────────────────────
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            HintChip(text = modelLabel, emphasized = true, onClick = onModelClick)
            HintChip(
                text = contextText,
                danger = contextPercent >= 90
            )
        }
    }
}

@Composable
private fun HintChip(
    text: String,
    emphasized: Boolean = false,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(999.dp)
    val borderColor =
        when {
            danger -> cs.error.copy(alpha = 0.45f)
            else -> cs.outlineVariant
        }
    val contentColor =
        when {
            danger -> cs.error
            emphasized -> cs.primary
            else -> cs.onSurfaceVariant
        }
    Box(
        modifier =
            Modifier
                .clip(shape)
                .border(1.dp, borderColor, shape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(text = text, fontSize = 11.sp, color = contentColor, maxLines = 1)
    }
}
