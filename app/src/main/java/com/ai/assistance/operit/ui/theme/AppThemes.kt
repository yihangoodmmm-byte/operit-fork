package com.ai.assistance.operit.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * iOS 高级设计感配色（Apple design language · apple-mode=app）。
 *
 * 语义色对齐 iOS 系统色：
 *   浅色  页面 #F2F2F7（systemGroupedBackground） 卡片 #FFFFFF（systemBackground）
 *         强调 #007AFF（systemBlue）  正文 #1C1C1E  次要文字 #6E6E73
 *   深色  页面 #000000（纯黑）  卡片 #1C1C1E / #2C2C2E
 *         强调 #0A84FF  正文 #F2F2F7  次要文字 #A1A1A6
 *
 * 层级靠「纯白 / 灰 / 纯黑」的表面关系，不用阴影、不用彩色 tint：
 * 显式补齐 surfaceContainer*，否则 M3 的 BottomSheet / Dialog / DropdownMenu
 * 会露出基线里的淡紫（#F3EDF7 / #211F26），是「廉价感」的来源之一。
 *
 * 这里只保留 iOS 一套唯一配色；其余枚举值仅为兼容旧偏好存储。
 */
enum class AppThemeFamily(val id: String, val displayName: String) {
    IOS_LIGHT("ios_light", "iOS 白"),
    IOS_DARK("ios_dark", "iOS 黑"),
    CLAUDE("claude", "Claude"),
    AICODE("aicode", "AiCode 蓝"),
    CC("cc", "Midnight"),
}

object AppThemes {

    // ── iOS 浅色 ────────────────────────────────────────────────
    private val IosLight = lightColorScheme(
        primary = Color(0xFF007AFF),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE5F0FF),
        onPrimaryContainer = Color(0xFF004A99),
        secondary = Color(0xFF5856D6),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE5E5FF),
        onSecondaryContainer = Color(0xFF1D1B4C),
        tertiary = Color(0xFF34C759),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFD9F6DF),
        onTertiaryContainer = Color(0xFF0B3D1C),
        background = Color(0xFFF2F2F7),
        onBackground = Color(0xFF1C1C1E),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1C1C1E),
        surfaceVariant = Color(0xFFF2F2F7),
        onSurfaceVariant = Color(0xFF6E6E73),
        surfaceTint = Color.Transparent,
        outline = Color(0xFFD1D1D6),
        outlineVariant = Color(0xFFE5E5EA),
        error = Color(0xFFFF3B30),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFE5E3),
        onErrorContainer = Color(0xFF8B0000),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF9F9FB),
        surfaceContainer = Color(0xFFF2F2F7),
        surfaceContainerHigh = Color(0xFFEBEBF0),
        surfaceContainerHighest = Color(0xFFE5E5EA),
        inverseSurface = Color(0xFF1C1C1E),
        inverseOnSurface = Color(0xFFF2F2F7),
        inversePrimary = Color(0xFF0A84FF),
        scrim = Color(0xFF000000),
    )

    // ── iOS 深色 ────────────────────────────────────────────────
    // 不用 #121212：iOS 深色模式的页面是纯黑，卡片才是 #1C1C1E / #2C2C2E。
    private val IosDark = darkColorScheme(
        primary = Color(0xFF0A84FF),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF004A99),
        onPrimaryContainer = Color(0xFFD6E9FF),
        secondary = Color(0xFF5E5CE6),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFF3D3B78),
        onSecondaryContainer = Color(0xFFE0DFFF),
        tertiary = Color(0xFF30D158),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFF0B3D1C),
        onTertiaryContainer = Color(0xFFD9F6DF),
        background = Color(0xFF000000),
        onBackground = Color(0xFFF2F2F7),
        surface = Color(0xFF1C1C1E),
        onSurface = Color(0xFFF2F2F7),
        surfaceVariant = Color(0xFF2C2C2E),
        onSurfaceVariant = Color(0xFFA1A1A6),
        surfaceTint = Color.Transparent,
        outline = Color(0xFF3A3A3C),
        outlineVariant = Color(0xFF2C2C2E),
        error = Color(0xFFFF453A),
        onError = Color(0xFF1C1C1E),
        errorContainer = Color(0xFF5C1A16),
        onErrorContainer = Color(0xFFFFDAD6),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF1C1C1E),
        surfaceContainer = Color(0xFF2C2C2E),
        surfaceContainerHigh = Color(0xFF2C2C2E),
        surfaceContainerHighest = Color(0xFF3A3A3C),
        inverseSurface = Color(0xFFF2F2F7),
        inverseOnSurface = Color(0xFF1C1C1E),
        inversePrimary = Color(0xFF007AFF),
        scrim = Color(0xFF000000),
    )

    /** 全局唯一配色：iOS 系统风，明暗跟随传入的 dark。其余家族仅为兼容旧偏好存储。 */
    fun schemeOf(family: AppThemeFamily, dark: Boolean = false): ColorScheme =
        if (dark) IosDark else IosLight

    /** 配色固定为 iOS 系统风。 */
    fun fromId(id: String?): AppThemeFamily = AppThemeFamily.IOS_LIGHT
}
