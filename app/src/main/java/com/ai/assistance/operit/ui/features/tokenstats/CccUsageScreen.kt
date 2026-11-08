package com.ai.assistance.operit.ui.features.tokenstats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.data.stats.TokenStatsDisplayModelBreakdown
import com.ai.assistance.operit.data.stats.TokenStatsLifetimeOverview

/**
 * 用量与余额。
 *
 * 说明：Operit 本身**已经有完整的统计实现**——TokenUsageDao 的聚合查询、
 * TokenUsageStatisticsViewModel（含区间、币种、自定义价格）、
 * TokenActivitySection 的热力图/连续天数，加起来十几万字符。
 * 缺的只是"把它们拼成一个页面 + 给一个入口"。
 *
 * 所以这个文件不重写统计逻辑，只做两件事：
 *   1. 按本项目的排版规范把数据摆成一页（行式、发丝线、无卡片堆叠）；
 *   2. 复用已有的 TokenActivitySection 展示日历热力图。
 */
@Composable
fun CccUsageScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: TokenUsageStatisticsViewModel =
        viewModel(
            factory =
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        TokenUsageStatisticsViewModel(context) as T
                }
        )
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.loadForEntry() }

    TokenStatsColorsProvider {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
        ) {
            item {
                UsageSectionLabel("累计")
                val lifetime = state.lifetime
                if (state.loading && lifetime == null) {
                    UsageRow("正在统计…", "")
                } else {
                    UsageRow("总 Token", lifetime?.let { formatCount(it.totals.totalTokens.knownSum) } ?: "—")
                    UsageRow("输入", lifetime?.let { formatCount(it.totals.totalInput.knownSum) } ?: "—")
                    UsageRow("输出", lifetime?.let { formatCount(it.totals.output.knownSum) } ?: "—")
                    UsageRow("请求数", lifetime?.let { formatCount(it.totals.requests) } ?: "—")
                    UsageRow(
                        "累计花费",
                        lifetime?.let { formatLifetimeMoney(it.totals.cost.knownAmount, it.totals.cost.currency) }
                            ?: "—"
                    )
                }
            }

            item {
                UsageSectionLabel("按模型")
                val models = state.lifetime?.displayModels.orEmpty()
                if (models.isEmpty()) {
                    UsageRow("暂无数据", "")
                } else {
                    models.forEachIndexed { index, item ->
                        UsageModelRow(item)
                        if (index == models.lastIndex) {
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                // 复用已有组件：热力图 + 连续天数 + 日/周/累计切换
                TokenActivitySection(
                    state = state.activity,
                    tokenDisplayUnit = state.tokenDisplayUnit
                )
            }
        }
    }
}

@Composable
private fun UsageSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun UsageRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun UsageModelRow(item: TokenStatsDisplayModelBreakdown) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatCount(item.totals.totalTokens.knownSum) + " tokens",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatLifetimeMoney(item.totals.cost.knownAmount, item.totals.cost.currency),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    }
}
