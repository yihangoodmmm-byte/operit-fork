package com.ai.assistance.operit.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * 调色板推导：10 个种子色 → 整套 Material 3 ColorScheme。
 *
 * 设计意图（不复制任何第三方实现，推导规则为本项目自写）：
 * 一套主题只描述"它长什么样"的 10 个关键色，其余 Material 槽位（primaryContainer、
 * surfaceContainer*、inverse*、errorContainer 等）由这里推出来。新增配色只需填种子，
 * 不会出现"改了 primary 忘了改 primaryContainer"这类不一致。
 *
 * 配色常量来源：cccodroid 0.4.10（Apache-2.0）gateway/web/style.css，见 [AiCodePalette.CC]。
 * 推导逻辑参考了 AiCode（GPL-3.0）的同一思路，但实现是独立编写的，未搬运其源码，
 * 因此不引入 GPL 传染。
 */
data class AiSeed(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color
)

/** 功能色跨主题固定，只随明暗切换——避免"成功"在某套配色里变成别的颜色。 */
private object FunctionalHues {
    val LightSuccess = Color(0xFF22C55E)
    val LightWarning = Color(0xFFF59E0B)
    val LightInfo = Color(0xFF0284C7)
    val LightError = Color(0xFFDC2626)
    val DarkSuccess = Color(0xFF4ADE80)
    val DarkWarning = Color(0xFFFBBF24)
    val DarkInfo = Color(0xFF38BDF8)
    val DarkError = Color(0xFFF87171)
}

object AiCodePalette {

    /** AiCode 默认蓝：品牌色 #2563EB，冷中性灰阶。 */
    val BLUE = AiSeed(
        primary = Color(0xFF2563EB),
        secondary = Color(0xFF0284C7),
        tertiary = Color(0xFF16A34A),
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFEAF4FF),
        onSurface = Color(0xFF0F172A),
        onSurfaceVariant = Color(0xFF475569),
        outline = Color(0xFFD1D1D6),
        outlineVariant = Color(0xFFE5E5EA)
    )

    /** AiCode 默认蓝 · 深色（对应它 DarkColorScheme 的种子）。 */
    val BLUE_DARK = AiSeed(
        primary = Color(0xFF60A5FA),
        secondary = Color(0xFF7DD3FC),
        tertiary = Color(0xFF22C55E),
        background = Color(0xFF07111F),
        surface = Color(0xFF0D1B2E),
        surfaceVariant = Color(0xFF13273F),
        onSurface = Color(0xFFEAF2FF),
        onSurfaceVariant = Color(0xFFB8C7DA),
        outline = Color(0xFF64748B),
        outlineVariant = Color(0xFF334155)
    )

    /**
     * CC 暖灰（cccodroid）：暖中性 + 陶土橙。
     *
     * 逐值取自 cccodroid 0.4.10 gateway/web/style.css（Apache-2.0）：
     *   深色    --bg #1f1e1d  --bg2 #262624  --bg3 #2f2e2b  --line #3a3935
     *           --fg #ece9e1  --fg2 #aaa59b  --fg3 #7f7a70  --accent #d97757
     *           --tool #8fb0d4 --ok #7fb069
     *   浅色    --bg #faf9f5  --bg2 #f3f1ea  --bg3 #ebe8df  --line #e0dcd0
     *           --fg #29261f  --fg2 #5d594f  --fg3 #8b867a  --accent #c96442
     *           --tool #2f6aa8 --ok #3d7a2a
     *
     * secondary 取它的 --tool（钢蓝），tertiary 取 --ok（橄榄绿），这两色在原设计里跨主题固定。
     */
    val CC = AiSeed(
        primary = Color(0xFFC96442),
        secondary = Color(0xFF2F6AA8),
        tertiary = Color(0xFF3D7A2A),
        background = Color(0xFFFAF9F5),
        surface = Color(0xFFF3F1EA),
        surfaceVariant = Color(0xFFEBE8DF),
        onSurface = Color(0xFF29261F),
        onSurfaceVariant = Color(0xFF5D594F),
        outline = Color(0xFF8B867A),
        outlineVariant = Color(0xFFE0DCD0)
    )

    val CC_DARK = AiSeed(
        primary = Color(0xFFD97757),
        secondary = Color(0xFF8FB0D4),
        tertiary = Color(0xFF7FB069),
        background = Color(0xFF1F1E1D),
        surface = Color(0xFF262624),
        surfaceVariant = Color(0xFF2F2E2B),
        onSurface = Color(0xFFECE9E1),
        onSurfaceVariant = Color(0xFFAAA59B),
        outline = Color(0xFF7F7A70),
        outlineVariant = Color(0xFF3A3935)
    )

    /** 由种子推导完整 ColorScheme。 */
    fun derive(seed: AiSeed, dark: Boolean): ColorScheme {
        val base = if (dark) darkColorScheme() else lightColorScheme()
        val error = if (dark) FunctionalHues.DarkError else FunctionalHues.LightError
        return base.copy(
            primary = seed.primary,
            onPrimary = seed.primary.contentOn(seed, dark),
            primaryContainer = seed.primary.containerOf(seed, dark),
            onPrimaryContainer = seed.primary.onContainerOf(dark),
            secondary = seed.secondary,
            onSecondary = seed.secondary.contentOn(seed, dark),
            secondaryContainer = seed.secondary.containerOf(seed, dark),
            onSecondaryContainer = seed.secondary.onContainerOf(dark),
            tertiary = seed.tertiary,
            onTertiary = seed.tertiary.contentOn(seed, dark),
            tertiaryContainer = seed.tertiary.containerOf(seed, dark),
            onTertiaryContainer = seed.tertiary.onContainerOf(dark),
            background = seed.background,
            onBackground = seed.onSurface,
            surface = seed.surface,
            onSurface = seed.onSurface,
            surfaceVariant = seed.surfaceVariant,
            onSurfaceVariant = seed.onSurfaceVariant,
            surfaceTint = if (dark) Color.Transparent else seed.surface,
            // M3 的 BottomSheet / Dialog / DropdownMenu 默认读 surfaceContainer*，
            // 不覆盖会露出 Material 基线的纯白与淡紫。
            surfaceContainerLowest = if (dark) lerp(seed.background, Color.Black, 0.25f) else seed.surface,
            surfaceContainerLow = if (dark) seed.background else lerp(seed.surface, seed.background, 0.45f),
            surfaceContainer = if (dark) seed.surface else seed.background,
            surfaceContainerHigh = seed.surfaceVariant,
            surfaceContainerHighest = lerp(seed.surfaceVariant, seed.onSurface, if (dark) 0.08f else 0.05f),
            surfaceBright = if (dark) lerp(seed.surface, seed.onSurface, 0.12f) else seed.surface,
            surfaceDim = if (dark) seed.background else lerp(seed.background, seed.onSurface, 0.10f),
            inverseSurface = seed.onSurface,
            inverseOnSurface = seed.surface,
            outline = seed.outline,
            outlineVariant = seed.outlineVariant,
            error = error,
            onError = error.contentOn(seed, dark),
            errorContainer = error.containerOf(seed, dark),
            onErrorContainer = error.onContainerOf(dark),
            scrim = Color.Black
        )
    }

    /** 该色作背景时，其上文字取近白还是近黑——按自身亮度判定，并混入主题色温避免纯黑白。 */
    private fun Color.contentOn(seed: AiSeed, dark: Boolean): Color {
        val light = if (dark) seed.onSurface else seed.surface
        val deep = if (dark) seed.background else seed.onSurface
        return if (luminance() > 0.45f) lerp(deep, this, 0.12f) else lerp(light, this, 0.08f)
    }

    /** 该色的容器底：向主题表面色靠拢，让状态色融进当前主题而不是突兀的独立色块。 */
    private fun Color.containerOf(seed: AiSeed, dark: Boolean): Color =
        lerp(this, seed.surface, if (dark) 0.78f else 0.86f)

    /** 容器底之上的文字：浅色压深、深色提亮，保证与容器底的对比度。 */
    private fun Color.onContainerOf(dark: Boolean): Color =
        if (dark) lerp(this, Color.White, 0.30f) else lerp(this, Color.Black, 0.45f)
}
