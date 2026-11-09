package com.ai.assistance.operit.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 三套内置配色。
 *
 * 为什么不用 Material You 动态取色：苹果风的设计前提是「界面有自己的确定性格」，
 * 跟随壁纸会让每个用户的界面都不一样，排版和对比度就无法保证。
 *
 * 为什么只定义一小组 role 而不是整张色板：Material3 的组件读的是语义 role，
 * 只要把 primary / surface / outline 这几组定准，其余组件会自动继承，
 * 少写一份配置就少一处不一致。
 */
enum class AppThemeFamily(val id: String, val displayName: String) {
    IOS_LIGHT("ios_light", "iOS 白"),
    IOS_DARK("ios_dark", "iOS 黑"),
    CLAUDE("claude", "Claude"),

    /** AiCode 默认蓝：品牌 #2563EB + 冷中性灰阶，浅/深各一套（见 [AiCodePalette.BLUE]）。 */
    AICODE("aicode", "AiCode 蓝"),

    /** Midnight：午夜极光冷色调，浅/深各一套（见 [AiCodePalette.CC]）。 */
    CC("cc", "Midnight"),
}

object AppThemes {

    // ── iOS 白 ────────────────────────────────────────────────
    // 底色是 iOS 系统灰（#F2F2F7），卡片纯白，强调色用系统蓝（#007AFF）。
    private val IosLight = lightColorScheme(
        primary = Color(0xFF30B0C7),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE5F0FF),
        onPrimaryContainer = Color(0xFF003D80),
        secondary = Color(0xFF5A5F66),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFEDEEF0),
        onSecondaryContainer = Color(0xFF2C2F33),
        tertiary = Color(0xFFFF9500),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFF2F2F7),
        onBackground = Color(0xFF1C1C1E),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1C1C1E),
        surfaceVariant = Color(0xFFE9E9EE),
        onSurfaceVariant = Color(0xFF6B6B70),
        outline = Color(0xFFD1D1D6),
        outlineVariant = Color(0xFFE5E5EA),
        error = Color(0xFFFF3B30),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFE5E3),
        onErrorContainer = Color(0xFF8B0000),
        surfaceTint = Color(0xFF30B0C7),
    )

    // ── iOS 黑 ────────────────────────────────────────────────
    // 不用纯黑：iOS 深色模式是 #000 背景 + #1C1C1E 卡片；
    // 这里背景取 #121212，卡片 #1C1C1E，层次靠亮度差而不是阴影。
    private val IosDark = darkColorScheme(
        primary = Color(0xFF40CBE0),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF0B3D6E),
        onPrimaryContainer = Color(0xFFD6E9FF),
        secondary = Color(0xFFA1A1A6),
        onSecondary = Color(0xFF1C1C1E),
        secondaryContainer = Color(0xFF2C2C2E),
        onSecondaryContainer = Color(0xFFE5E5EA),
        tertiary = Color(0xFFFF9F0A),
        onTertiary = Color(0xFF00330F),
        background = Color(0xFF121212),
        onBackground = Color(0xFFF2F2F7),
        surface = Color(0xFF1C1C1E),
        onSurface = Color(0xFFF2F2F7),
        surfaceVariant = Color(0xFF2C2C2E),
        onSurfaceVariant = Color(0xFFA1A1A6),
        outline = Color(0xFF3A3A3C),
        outlineVariant = Color(0xFF2C2C2E),
        error = Color(0xFFFF453A),
        onError = Color(0xFF1C1C1E),
        errorContainer = Color(0xFF5C1A16),
        onErrorContainer = Color(0xFFFFDAD6),
        surfaceTint = Color(0xFF40CBE0),
    )

    // ── Claude ────────────────────────────────────────────────
    // Claude 的界面是暖色纸感：背景 #F5F4EF，正文深棕 #2C2825，
    // 强调色是它标志性的陶土橙 #C25A34。这里把中性色整体偏暖。
    private val Claude = lightColorScheme(
        primary = Color(0xFFC25A34),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFF7E7DF),
        onPrimaryContainer = Color(0xFF5A2410),
        secondary = Color(0xFF6B6259),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFEDE7DF),
        onSecondaryContainer = Color(0xFF3B352F),
        tertiary = Color(0xFF7A8B5A),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFF5F4EF),
        onBackground = Color(0xFF2C2825),
        surface = Color(0xFFFBFAF7),
        onSurface = Color(0xFF2C2825),
        surfaceVariant = Color(0xFFEDEAE3),
        onSurfaceVariant = Color(0xFF6B6259),
        outline = Color(0xFFD8D3C9),
        outlineVariant = Color(0xFFE8E4DC),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        surfaceTint = Color(0xFFC25A34),
    )

    /**
     * 全局唯一配色：iOS 系统风（系统蓝 #007AFF/#0A84FF + 系统灰 #F2F2F7/#121212），
     * 明暗跟随系统。其余家族枚举只为兼容旧偏好存储，一律映射到同一套配色。
     */
    fun schemeOf(family: AppThemeFamily, dark: Boolean = false): ColorScheme =
        if (dark) IosDark else IosLight

    /** 配色已固定为 iOS 系统风，任何输入都返回同一家族。 */
    fun fromId(id: String?): AppThemeFamily = AppThemeFamily.IOS_LIGHT
}
