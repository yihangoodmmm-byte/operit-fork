package com.ai.assistance.operit.ui.features.chat.v2

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.data.model.ModelConfigSummary
import com.ai.assistance.operit.data.model.getValidModelIndex

/**
 * 模型快捷切换浮窗。
 *
 * 做法参照太墟的 `BotModelPicker`：菜单里**只用 DropdownMenuItem**，不自己搭滚动容器。
 *
 * 这么做是因为踩过一个坑：DropdownMenu 测量时会向子节点询问 intrinsic 尺寸，
 * 而 LazyColumn / BoxWithConstraints 这类基于 SubcomposeLayout 的组件不支持 intrinsic 测量，
 * 会直接抛 "Asking for intrinsic measurements of SubcomposeLayout layouts is not supported"。
 * DropdownMenuItem 是 Material 为菜单量身做的，超长时菜单自身会处理滚动，不需要 LazyColumn。
 *
 * 数据来源与旧输入区一致：
 * - 候选：ModelConfigManager.configSummariesFlow（一个配置的 modelName 可能是逗号分隔的多个模型）
 * - 当前选中：FunctionalConfigManager.functionConfigMappingWithIndexFlow[CHAT]
 */
@Composable
fun CccModelPicker(
    expanded: Boolean,
    onDismiss: () -> Unit,
    summaries: List<ModelConfigSummary>,
    currentConfigId: String,
    currentModelIndex: Int,
    onPick: (configId: String, modelIndex: Int) -> Unit,
    onManageModels: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    // 展平成「配置 × 模型」候选：一个配置里 modelName 可能是逗号分隔的多个模型
    val rows =
        summaries.flatMap { summary ->
            summary.modelName
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .mapIndexed { index, model -> Triple(summary, index, model) }
        }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = cs.surface,
    ) {
        // 标题用普通 Text：DropdownMenu 内部本来就是 Column，
        // 放 Text 不会触发 SubcomposeLayout 的 intrinsic 测量问题（LazyColumn 才会）。
        Text(
            text = "切换模型",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
        )

        rows.forEach { row ->
            val summary = row.first
            val index = row.second
            val model = row.third
            val validIndex = getValidModelIndex(summary.modelName, index)
            val selected = summary.id == currentConfigId && validIndex == currentModelIndex
            DropdownMenuItem(
                text = {
                    Text(
                        text = model,
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
                onClick = { onPick(summary.id, validIndex) }
            )
        }

        HorizontalDivider(color = cs.outlineVariant)

        DropdownMenuItem(
            text = {
                Text(
                    text = "管理模型配置…",
                    fontSize = 13.sp,
                    color = cs.onSurfaceVariant
                )
            },
            onClick = onManageModels
        )
    }
}
