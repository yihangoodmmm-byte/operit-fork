package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.runtime.Composable

/**
 * 主题设置入口。
 *
 * 不再转发到原来的 ThemeSettingsContent（2806 行的多标签编辑器）：
 * 那套 UI 是给「逐项调校颜色/背景/气泡/输入框」准备的，与当前
 * 「三套内置配色 + 玻璃开关」的目标不符，而且入口被摘掉后已经没有引用。
 */
@Composable
fun ThemeSettingsScreen(onBackPressed: () -> Unit = {}) {
    ThemeSettingsSimpleScreen(onBackPressed = onBackPressed)
}
