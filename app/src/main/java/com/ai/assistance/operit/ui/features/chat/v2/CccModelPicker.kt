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
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigSummary
import com.ai.assistance.operit.data.model.getValidModelIndex

/**
 * 模型快捷切换浮窗（按供应商分组）。
 *
 * 做法参照太墟的 `BotModelPicker`：菜单里只用 DropdownMenuItem，不自己搭滚动容器。
 *
 * 数据来源与旧输入区一致：
 * - 候选：ModelConfigManager.configSummariesFlow（一个配置的 modelName 可能是逗号分隔的多个模型）
 * - 当前选中：FunctionalConfigManager.functionConfigMappingWithIndexFlow[CHAT]
 *
 * 分组：按 apiProviderTypeId 把配置归到各自供应商下，每个供应商一个小节标题，
 * 组内是该供应商的所有「配置 × 模型」条目。
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

    // 按供应商分组（groupBy 返回 LinkedHashMap，保持 summaries 原始顺序）
    val groupedSummaries: List<Pair<String, List<ModelConfigSummary>>> =
        summaries.groupBy { it.apiProviderTypeId }.toList()

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = cs.surface,
    ) {
        Text(
            text = "切换模型",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
        )

        groupedSummaries.forEachIndexed { groupIndex, (providerId, groupSummaries) ->
            // 分组头：除第一组外，上方加一条细分隔线
            if (groupIndex > 0) {
                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f))
            }
            Text(
                text = providerDisplayName(providerId),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
                color = cs.onSurfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp)
            )

            groupSummaries.forEach { summary ->
                val models =
                    summary.modelName
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                models.forEachIndexed { index, model ->
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
            }
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

/** 供应商 id → 友好显示名。 */
private fun providerDisplayName(providerTypeId: String): String {
    return when (ApiProviderType.fromProviderTypeId(providerTypeId)) {
        ApiProviderType.OPENAI,
        ApiProviderType.OPENAI_RESPONSES,
        ApiProviderType.OPENAI_CODEX,
        ApiProviderType.OPENAI_GENERIC,
        ApiProviderType.OPENAI_RESPONSES_GENERIC,
        -> "OpenAI"

        ApiProviderType.XAI -> "xAI · Grok"
        ApiProviderType.ANTHROPIC, ApiProviderType.ANTHROPIC_GENERIC -> "Claude"
        ApiProviderType.GOOGLE, ApiProviderType.GEMINI_GENERIC -> "Gemini"
        ApiProviderType.BAIDU -> "百度 · 文心"
        ApiProviderType.ALIYUN -> "阿里云 · 通义"
        ApiProviderType.XUNFEI -> "讯飞 · 星火"
        ApiProviderType.ZHIPU -> "智谱 · GLM"
        ApiProviderType.BAICHUAN -> "百川"
        ApiProviderType.MOONSHOT -> "月之暗面 · Kimi"
        ApiProviderType.MIMO -> "小米 · MiMo"
        ApiProviderType.DEEPSEEK -> "DeepSeek"
        ApiProviderType.MISTRAL -> "Mistral"
        ApiProviderType.SILICONFLOW -> "硅基流动"
        ApiProviderType.IFLOW -> "iFlow"
        ApiProviderType.OPENROUTER -> "OpenRouter"
        ApiProviderType.OPENCODE -> "OpenCode"
        ApiProviderType.FOUR_ROUTER -> "4Router"
        ApiProviderType.NOUS_PORTAL -> "Nous"
        ApiProviderType.INFINIAI -> "无问芯穹"
        ApiProviderType.ALIPAY_BAILING -> "支付宝百灵"
        ApiProviderType.DOUBAO -> "豆包"
        ApiProviderType.NVIDIA -> "NVIDIA"
        ApiProviderType.LMSTUDIO -> "LM Studio"
        ApiProviderType.OLLAMA -> "Ollama"
        ApiProviderType.OPENAI_LOCAL -> "OpenAI 本地"
        ApiProviderType.MNN -> "MNN 本地"
        ApiProviderType.LLAMA_CPP -> "llama.cpp"
        ApiProviderType.PPINFRA -> "派欧云"
        ApiProviderType.NOVITA -> "Novita"
        ApiProviderType.MINIMAX -> "MiniMax"
        ApiProviderType.OTHER -> "其他"
        null ->
            providerTypeId
                .replace('_', ' ')
                .lowercase()
                .replaceFirstChar { it.uppercase() }
    }
}
