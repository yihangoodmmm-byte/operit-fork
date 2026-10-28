package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.core.chat.TaskStepExtractor

/**
 * 本轮任务预览条。
 *
 * 默认收起为一行进度（「本轮 5 步 · 3/5」），点开才是逐条步骤。
 * 为什么不常驻展开：聊天区高度宝贵，步骤多时会喧宾夺主。
 */
@Composable
fun TaskPreviewBar(
    steps: List<TaskStepExtractor.TaskStep>,
    modifier: Modifier = Modifier,
) {
    if (steps.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val doneCount = steps.count { it.state == TaskStepExtractor.StepState.DONE }
    val hasRunning = steps.any { it.state == TaskStepExtractor.StepState.RUNNING }
    val hasFailed = steps.any { it.state == TaskStepExtractor.StepState.FAILED }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (hasRunning) "●" else if (hasFailed) "✗" else "✓",
                fontSize = 12.sp,
                color = when {
                    hasRunning -> MaterialTheme.colorScheme.primary
                    hasFailed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                }
            )
            Text(
                text = "本轮 " + steps.size + " 步 · " + doneCount + "/" + steps.size,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (expanded) "收起" else "展开",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            steps.forEach { step ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = when (step.state) {
                            TaskStepExtractor.StepState.DONE -> "✓"
                            TaskStepExtractor.StepState.FAILED -> "✗"
                            TaskStepExtractor.StepState.RUNNING -> "●"
                        },
                        fontSize = 12.sp,
                        color = when (step.state) {
                            TaskStepExtractor.StepState.DONE -> MaterialTheme.colorScheme.primary
                            TaskStepExtractor.StepState.FAILED -> MaterialTheme.colorScheme.error
                            TaskStepExtractor.StepState.RUNNING -> MaterialTheme.colorScheme.primary
                        }
                    )
                    Text(text = step.toolName, fontSize = 12.sp)
                }
            }
        }
    }
}
