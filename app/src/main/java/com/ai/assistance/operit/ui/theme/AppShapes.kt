package com.ai.assistance.operit.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 全局形状。
 *
 * 苹果风的关键不是圆角大，而是**圆角在整个界面里成体系**：
 * 小控件、卡片、对话框按同一套比例递增，而不是各写各的 dp。
 * Material3 的组件全部读 Shapes 语义名，这里定义一次就全局生效。
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
