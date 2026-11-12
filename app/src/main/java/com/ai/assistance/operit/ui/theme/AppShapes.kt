package com.ai.assistance.operit.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 全局形状（iOS 圆角体系）。
 *
 * iOS 的圆角是成体系的：分组卡片 / 弹层用大圆角（16–24dp），
 * 控件与按钮用小圆角（8–12dp），而不是各写各的 dp。
 * Material3 组件读 Shapes 语义名，这里定义一次就作用于全项目的
 * Card / Dialog / BottomSheet / OutlinedTextField。
 *
 * 层级：extraSmall 8 · small 10 · medium 12 · large 16 · extraLarge 24。
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
