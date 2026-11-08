package com.ai.assistance.operit.ui.features.chat.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.data.model.ModelConfigSummary
import com.ai.assistance.operit.data.model.getValidModelIndex

/**
 * 模型快捷切换浮窗（对齐 cccodroid 的模型选择器）。
 *
 * 为什么要有它：上一版把模型芯片的点击直接接到「模型配置页」——那是**配置**，不是**切换**。
 * 聊天中换模型是高频轻操作，应该原地弹候选、点一下生效；只有新增/编辑配置时才跳配置页，
 * 放在列表底部的「管理模型配置…」里。
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
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = cs.surface,
    ) {
        Column(modifier = Modifier.widthIn(min = 200.dp, max = 300.dp)) {
            Text(
                text = "切换模型",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp)
            )

            // 展平成「配置 × 模型」候选
            val rows =
                summaries.flatMap { summary ->
                    summary.modelName.split(',').mapIndexed { index, model ->
                        Triple(summary, index, model.trim())
                    }
                }

            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(count = rows.size) { i ->
                    val (summary, index, model) = rows[i]
                    val validIndex = getValidModelIndex(summary.modelName, index)
                    val selected =
                        summary.id == currentConfigId && validIndex == currentModelIndex
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(summary.id, validIndex) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = cs.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            Spacer(modifier = Modifier.size(14.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = model.ifBlank { summary.name },
                            fontSize = 14.sp,
                            color = if (selected) cs.primary else cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (summaries.size > 1) {
                            Text(
                                text = summary.name,
                                fontSize = 10.sp,
                                color = cs.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = cs.outlineVariant)

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onManageModels() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(cs.onSurfaceVariant.copy(alpha = 0.5f))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "管理模型配置…",
                    fontSize = 13.sp,
                    color = cs.onSurfaceVariant
                )
            }
        }
    }
}
