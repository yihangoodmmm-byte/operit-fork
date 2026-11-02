package com.ai.assistance.operit.ui.features.settings.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.ThemePreferenceValues
import com.ai.assistance.operit.ui.components.CustomScaffold
import com.ai.assistance.operit.ui.theme.AppThemeFamily
import com.ai.assistance.operit.ui.theme.AppThemes
import com.ai.assistance.operit.ui.theme.isLiquidGlassSupported
import com.ai.assistance.operit.ui.theme.rememberActiveThemePreferenceSnapshot
import kotlinx.coroutines.launch

/**
 * 极简主题页：三套配色 + 两个玻璃开关。
 *
 * 为什么不用原来的多标签编辑器：它面向「逐项调校」，
 * 对「换一套配色」这个目标来说全是噪音；重建一个只有必要项的页面，
 * 用户决策成本最低。
 */
@Composable
fun ThemeSettingsSimpleScreen(onBackPressed: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot = rememberActiveThemePreferenceSnapshot()
    val manager = remember(context) { ActivePromptManager.getInstance(context) }
    val glassSupported = remember { isLiquidGlassSupported() }

    var currentFamily by remember(snapshot) {
        mutableStateOf(
            AppThemeFamily.entries.firstOrNull { it.id == snapshot.values.string("app_theme_family") }
                ?: AppThemeFamily.IOS_LIGHT
        )
    }
    var bubbleGlass by remember(snapshot) {
        mutableStateOf(snapshot.values.booleans["bubble_ai_bubble_liquid_glass"] == true)
    }
    var inputGlass by remember(snapshot) {
        mutableStateOf(snapshot.values.booleans["chat_input_liquid_glass"] == true)
    }

    fun apply(transform: ThemePreferenceValues.() -> ThemePreferenceValues) {
        scope.launch {
            manager.mutateActiveThemeForPrompt(
                target = ActivePrompt.CharacterCard(CharacterCardManager.DEFAULT_CHARACTER_CARD_ID)
            ) { values -> values.transform() }
        }
    }

    CustomScaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text("外观", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("选择一套配色，切换后立即生效。", fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))

            AppThemeFamily.entries.forEach { family ->
                val scheme = AppThemes.schemeOf(family)
                val selected = family == currentFamily
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(scheme.surface)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) scheme.primary else scheme.outlineVariant,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable {
                            currentFamily = family
                            apply { withString("app_theme_family", family.id) }
                            Toast.makeText(context, "已切换到 " + family.displayName, Toast.LENGTH_SHORT).show()
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(scheme.primary, scheme.background, scheme.surface).forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(1.dp, Color.Black.copy(alpha = 0.08f), CircleShape)
                            )
                        }
                    }
                    Spacer(Modifier.size(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = family.displayName,
                            color = scheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp
                        )
                        Text(
                            text = when (family) {
                                AppThemeFamily.CLAUDE -> "暖纸底 · 陶土橙"
                                AppThemeFamily.IOS_DARK -> "深色 · 系统蓝"
                                else -> "系统灰底 · 纯白卡片"
                            },
                            color = scheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    if (selected) {
                        Text("OK", color = scheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("玻璃效果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (glassSupported) {
                    "液态玻璃需要 Android 13 及以上，当前设备支持。"
                } else {
                    "当前系统版本不支持液态玻璃，开关不会生效。"
                },
                fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))

            GlassRow(
                title = "消息气泡毛玻璃",
                subtitle = "AI 与用户气泡使用液态玻璃材质",
                checked = bubbleGlass,
                onCheckedChange = { on ->
                    bubbleGlass = on
                    apply {
                        withBoolean("bubble_ai_bubble_liquid_glass", on)
                            .withBoolean("bubble_user_bubble_liquid_glass", on)
                    }
                }
            )
            GlassRow(
                title = "输入框毛玻璃",
                subtitle = "聊天输入框使用液态玻璃材质",
                checked = inputGlass,
                onCheckedChange = { on ->
                    inputGlass = on
                    apply { withBoolean("chat_input_liquid_glass", on) }
                }
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GlassRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
