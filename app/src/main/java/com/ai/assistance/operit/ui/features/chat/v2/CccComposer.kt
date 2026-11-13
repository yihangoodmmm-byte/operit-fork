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
 * 输入区（长方形样式）。
 *
 * 结构：文本输入在上（多行可生长），底部一行左侧放「模型 / 推理档位 / 上下文」芯片，
 * 发送键位于右下角。上下文芯片可点击查看具体 token 消耗。
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
    /** 模型快捷切换浮窗（由调用方提供，锚定在模型芯片上）。 */
    modelPicker: @Composable () -> Unit = {},
    /**
     * 推理档位芯片的文案。为 null 表示当前模型不支持推理档位（TOGGLE_ONLY / UNSUPPORTED），
     * 此时不渲染这颗芯片。
     */
    reasoningLabel: String? = null,
    onReasoningClick: (() -> Unit)? = null,
    /** 推理档位浮窗，锚定在输入区根部。 */
    reasoningPicker: @Composable () -> Unit = {},
    /** 点击上下文芯片：查看具体 token 消耗明细。 */
    onContextClick: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val fieldShape = RoundedCornerShape(12.dp)

    Box {
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .background(cs.background)
                    .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp)
        ) {
            // 长方形输入容器
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(fieldShape)
                        .background(cs.surface)
                        .border(1.dp, cs.outlineVariant.copy(alpha = 0.7f), fieldShape)
                        .padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 6.dp)
            ) {
                // 文本输入（上部，多行可生长）
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
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
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                }

                // 底部工具行：左侧芯片 + 右侧发送
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box {
                            HintChip(text = modelLabel, emphasized = true, onClick = onModelClick)
                            modelPicker()
                        }
                        if (reasoningLabel != null && onReasoningClick != null) {
                            Box {
                                HintChip(text = reasoningLabel, onClick = onReasoningClick)
                            }
                        }
                        HintChip(
                            text = contextText,
                            danger = contextPercent >= 90,
                            onClick = onContextClick
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // 发送 / 停止：触控区 44dp，视觉 34dp 圆，右下角
                    Box(
                        modifier =
                            Modifier
                                .size(44.dp)
                                .clickable(enabled = canSend || isProcessing) {
                                    if (isProcessing) onStop() else onSend()
                                },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isProcessing) cs.error
                                        else if (canSend) cs.primary
                                        else cs.surfaceVariant
                                    ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isProcessing) Icons.Default.Close else Icons.AutoMirrored.Filled.Send,
                                contentDescription = if (isProcessing) "停止" else "发送",
                                tint =
                                    if (isProcessing) cs.onError
                                    else if (canSend) cs.onPrimary
                                    else cs.onSurfaceVariant.copy(alpha = 0.38f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
        // 推理档位浮窗锚定在 composer 全宽根部，居中浮于输入栏上方
        reasoningPicker()
    }
}

/**
 * 状态芯片。形态：浅填充 + 999dp 全圆角 + 11sp。
 */
@Composable
private fun HintChip(
    text: String,
    emphasized: Boolean = false,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(999.dp)
    val backgroundColor =
        when {
            danger -> cs.error.copy(alpha = 0.12f)
            emphasized -> cs.primary.copy(alpha = 0.12f)
            else -> cs.onSurface.copy(alpha = 0.07f)
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
                .background(backgroundColor)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(text = text, fontSize = 11.sp, color = contentColor, maxLines = 1)
    }
}
