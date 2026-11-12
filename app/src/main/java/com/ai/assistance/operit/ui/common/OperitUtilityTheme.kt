package com.ai.assistance.operit.ui.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.ai.assistance.operit.ui.theme.AppShapes
import com.ai.assistance.operit.ui.theme.AppThemeFamily
import com.ai.assistance.operit.ui.theme.AppThemes
import com.ai.assistance.operit.ui.theme.Typography

/**
 * 工具 / 浮层等脱离主界面树的场景，统一复用 iOS 配色 / 字体 / 形状，
 * 避免这些窗口露出 Material3 基线的淡紫与默认字重，破坏整体一致性。
 */
@Composable
fun OperitUtilityTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppThemes.schemeOf(AppThemeFamily.IOS_LIGHT, isSystemInDarkTheme()),
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
