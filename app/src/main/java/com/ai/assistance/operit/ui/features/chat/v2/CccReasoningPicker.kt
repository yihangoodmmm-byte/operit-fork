package com.ai.assistance.operit.ui.features.chat.v2

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 推理档位的一个可选项。
 *
 * 为什么不直接用 ThinkingQualityOption：那个类型在 llmprovider 包里是 internal，
 * 让 UI 组件签名依赖它会把这个细节泄漏到排版层；这里只传 id + 展示名。
 */
data class CccReasoningOption(val id: String, val label: String)

/**
 * 推理档位选择浮窗。
 *
 * 数据来自 ThinkingQualityMappingRegistry：每个「模型配置 × 模型」可以自带
 * thinkingConfigurations 规则，解析出的 options 才是这个模型真正支持的档位。
 * 所以档位不是全应用固定的几个常量，换模型要重新解析。
 *
 * 形态与 CccModelPicker 一致：只用 DropdownMenuItem，不用 LazyColumn
 * （DropdownMenu 会向子节点要 intrinsic 尺寸，SubcomposeLayout 类组件不支持）。
 */
@Composable
fun CccReasoningPicker(
    expanded: Boolean,
    onDismiss: () -> Unit,
    options: List<CccReasoningOption>,
    currentId: String,
    onPick: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = cs.surface,
    ) {
        Text(
            text = "推理强度",
            fontSize = 11.sp,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
        )
        options.forEach { option ->
            val selected = option.id == currentId
            DropdownMenuItem(
                text = {
                    Text(
                        text = option.label,
                        fontSize = 14.sp,
                        color = if (selected) cs.primary else cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                trailingIcon =
                    if (selected) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = cs.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        null
                    },
                onClick = { onPick(option.id) }
            )
        }
    }
}
