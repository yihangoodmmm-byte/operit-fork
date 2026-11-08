package com.ai.assistance.operit.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.core.tools.system.ShizukuAuthorizer
import com.ai.assistance.operit.core.tools.system.action.ActionListenerFactory
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.data.preferences.androidPermissionPreferences
import com.ai.assistance.operit.ui.common.NavItem
import com.ai.assistance.operit.ui.main.screens.ScreenRouteRegistry
import com.ai.assistance.operit.ui.main.navigation.NavigationEntrySpec
import com.ai.assistance.operit.ui.main.screens.Screen
import com.ai.assistance.operit.ui.theme.liquidGlass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private data class SidebarPermissionStatus(
        val badgeTextResId: Int
)

private suspend fun resolveSidebarPermissionStatus(
        context: android.content.Context,
        preferredPermissionLevel: AndroidPermissionLevel?
): SidebarPermissionStatus {
        return when (preferredPermissionLevel) {
                null,
                AndroidPermissionLevel.STANDARD ->
                        SidebarPermissionStatus(
                                badgeTextResId = R.string.sidebar_status_normal
                        )
                AndroidPermissionLevel.DEBUGGER ->
                        when {
                                !ShizukuAuthorizer.isShizukuInstalled(context) ->
                                        SidebarPermissionStatus(
                                                badgeTextResId = R.string.status_not_installed
                                        )
                                !ShizukuAuthorizer.isShizukuServiceRunning() ->
                                        SidebarPermissionStatus(
                                                badgeTextResId = R.string.status_not_running
                                        )
                                ShizukuAuthorizer.hasShizukuPermission() ->
                                        SidebarPermissionStatus(
                                                badgeTextResId = R.string.sidebar_status_normal
                                        )
                                else ->
                                        SidebarPermissionStatus(
                                                badgeTextResId = R.string.unauthorized
                                        )
                        }
                AndroidPermissionLevel.ACCESSIBILITY,
                AndroidPermissionLevel.ADMIN,
                AndroidPermissionLevel.ROOT -> {
                        val permissionStatus =
                                ActionListenerFactory.getListener(context, preferredPermissionLevel)
                                        .hasPermission()
                        SidebarPermissionStatus(
                                badgeTextResId =
                                        if (permissionStatus.granted) {
                                                R.string.sidebar_status_normal
                                        } else {
                                                R.string.unauthorized
                                        }
                        )
                }
        }
}

/** Content for the expanded navigation drawer */
@Composable
fun DrawerContent(
        navItems: List<NavItem>,
        pluginEntries: List<NavigationEntrySpec>,
        selectedItem: NavItem?,
        selectedRouteId: String,
        isNetworkAvailable: Boolean,
        networkType: String,
        appearance: NavigationDrawerAppearance,
        topContentPadding: Dp? = null,
        scope: CoroutineScope,
        drawerState: androidx.compose.material3.DrawerState,
        onScreenSelected: (Screen) -> Unit,
        onNavigationEntrySelected: (NavigationEntrySpec) -> Unit
) {
        val context = LocalContext.current
        val userPreferencesManager = remember(context) { UserPreferencesManager.getInstance(context) }
        val softwareIdentity by
                userPreferencesManager.softwareIdentity.collectAsState(
                        initial = UserPreferencesManager.SOFTWARE_IDENTITY_OPERIT
                )
        val preferredPermissionLevel by
                androidPermissionPreferences.preferredPermissionLevelFlow.collectAsState(initial = null)
        val drawerBrandName =
                if (softwareIdentity == UserPreferencesManager.SOFTWARE_IDENTITY_LINGSHU) {
                        context.getString(R.string.software_identity_option_lingshu)
                } else {
                        context.getString(R.string.software_identity_option_operit)
                }
        val bottomInset =
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val resolvedTopContentPadding =
                topContentPadding ?:
                WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val fixedBottomItems = remember {
                setOf(NavItem.Settings)
        }
        val quickActionItems = remember {
                setOf(NavItem.Packages, NavItem.Workflow)
        }
        // iOS 化：统计卡已删除，packageManager / workflowRepository / 三个 produceState
        // 都不再有消费者（原先每切一次页面要白做三次磁盘与包管理查询），一并移除。
        val primaryNavItems =
                remember(navItems) {
                        navItems.filterNot {
                                it in fixedBottomItems ||
                                        it in quickActionItems ||
                                        it == NavItem.ShizukuCommands
                        }
                }
        val handleScreenSelection: (Screen) -> Unit = { screen ->
                val shouldCloseBeforeNavigate =
                        drawerState.currentValue == DrawerValue.Open ||
                                drawerState.targetValue == DrawerValue.Open

                scope.launch {
                        if (shouldCloseBeforeNavigate) {
                                drawerState.close()
                        }
                        onScreenSelected(screen)
                }
        }
        val handleNavItemClick: (NavItem) -> Unit = { item ->
                handleScreenSelection(ScreenRouteRegistry.defaultScreenForNavItem(item))
        }
        // 「新对话」：直接调 ChatHistoryManager 建一个当前会话，再切到聊天页。
        // 抽屉拿不到 ChatViewModel，但 ChatHistoryManager 是进程单例，行为等价。
        // 最近对话（cccodroid 抽屉的核心内容，Operit 原来没有）
        val drawerChatHistories by
                ChatHistoryManager.getInstance(context).chatHistoriesFlow.collectAsState(initial = emptyList())
        val drawerCurrentChatId by
                ChatHistoryManager.getInstance(context).currentChatIdFlow.collectAsState(initial = null)
        val handleSwitchChat: (String) -> Unit = { chatId ->
                scope.launch {
                        runCatching { ChatHistoryManager.getInstance(context).setCurrentChatId(chatId) }
                        if (drawerState.currentValue == DrawerValue.Open) {
                                drawerState.close()
                        }
                        handleScreenSelection(ScreenRouteRegistry.defaultScreenForNavItem(NavItem.AiChat))
                }
        }
        val handleNewChat: () -> Unit = {
                scope.launch {
                        runCatching {
                                ChatHistoryManager.getInstance(context).createNewChat(setAsCurrentChat = true)
                        }
                        if (drawerState.currentValue == DrawerValue.Open) {
                                drawerState.close()
                        }
                        handleScreenSelection(ScreenRouteRegistry.defaultScreenForNavItem(NavItem.AiChat))
                }
        }
        val handleNavigationEntryClick: (NavigationEntrySpec) -> Unit = { entry ->
                val shouldCloseBeforeNavigate =
                        drawerState.currentValue == DrawerValue.Open ||
                                drawerState.targetValue == DrawerValue.Open
                scope.launch {
                        if (shouldCloseBeforeNavigate) {
                                drawerState.close()
                        }
                        onNavigationEntrySelected(entry)
                }
        }

        Column(
                modifier =
                        Modifier.fillMaxHeight()
                                .padding(
                                        top = resolvedTopContentPadding,
                                        end = 8.dp,
                                        bottom = bottomInset
                                )
        ) {
                // 结构照 cccodroid 的 drawer：
                //   side-head（品牌）→ side-new（新对话）→ side-nav（导航，固定）
                //   → side-recent（最近对话，flex:1 独立滚动）→ 底部账户行
                // 之前整块共用一个 verticalScroll，最近对话一多就把导航项顶到屏幕外，
                // 必须往下滚才能看到"插件/自动化任务"——这是错的层级。
                NewSidebarTopContent(
                                selectedItem = selectedItem,
                                pluginEntries = pluginEntries,
                                selectedRouteId = selectedRouteId,
                                brandName = drawerBrandName,
                                appearance = appearance,
                                navItems = primaryNavItems,
                                onNavItemClick = handleNavItemClick,
                                onNavigationEntryClick = handleNavigationEntryClick,
                                onNewChat = handleNewChat,
                        )

                // 最近对话占据剩余全部高度，自己滚动（cccodroid 的 .side-recent{flex:1;overflow-y:auto}）
                DrawerRecentChats(
                        chats = drawerChatHistories.take(30),
                        currentChatId = drawerCurrentChatId,
                        appearance = appearance,
                        onSelect = handleSwitchChat,
                        modifier = Modifier.weight(1f)
                )

                HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        thickness = 0.5.dp,
                        color = appearance.dividerColor.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(8.dp))

                DrawerBottomShortcutRow(
                        selectedItem = selectedItem,
                        appearance = appearance,
                        onNavItemClick = handleNavItemClick
                )
        }
}

/** Content for the collapsed navigation drawer (for tablet mode) */
@Composable
fun CollapsedDrawerContent(
        navItems: List<NavItem>,
        pluginEntries: List<NavigationEntrySpec>,
        selectedItem: NavItem?,
        selectedRouteId: String,
        isNetworkAvailable: Boolean,
        appearance: NavigationDrawerAppearance,
        onScreenSelected: (Screen) -> Unit,
        onNavigationEntrySelected: (NavigationEntrySpec) -> Unit
) {
        Column(
                modifier =
                        Modifier.fillMaxHeight()
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                        modifier =
                                Modifier.size(44.dp)
                                        .liquidGlass(
                                                enabled = appearance.buttonLiquidGlassEnabled,
                                                shape = CircleShape,
                                                containerColor = appearance.buttonContainerColor,
                                                shadowElevation = 5.dp,
                                                borderWidth = 0.5.dp,
                                                blurRadius = 14.dp,
                                                overlayAlphaBoost = 0.05f,
                                                enableLens = false
                                        )
                                        .clip(CircleShape),
                        color = Color.Transparent,
                        shape = CircleShape
                ) {
                        IconButton(onClick = { }) {
                                Icon(
                                        imageVector =
                                                if (isNetworkAvailable) Icons.Default.Wifi
                                                else Icons.Default.WifiOff,
                                        contentDescription = stringResource(id = R.string.network_status_label),
                                        tint = appearance.statusAvailableColor,
                                        modifier = Modifier.size(24.dp)
                                )
                        }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(
                        modifier = Modifier.fillMaxWidth(0.6f),
                        color = appearance.dividerColor
                )
                Spacer(modifier = Modifier.height(16.dp))

                for (item in navItems) {
                        val selectedGlassOverlayColor =
                                if (selectedItem == item) {
                                        appearance.selectedContainerColor.copy(alpha = 0.18f)
                                } else {
                                        Color.Transparent
                                }
                        Surface(
                                modifier =
                                        Modifier.padding(vertical = 8.dp)
                                                .size(44.dp)
                                                .liquidGlass(
                                                        enabled = appearance.buttonLiquidGlassEnabled,
                                                        shape = CircleShape,
                                                        containerColor =
                                                                appearance.buttonContainerColor,
                                                        shadowElevation =
                                                                if (selectedItem == item) 6.dp else 5.dp,
                                                        borderWidth = 0.5.dp,
                                                        blurRadius = 14.dp,
                                                        overlayAlphaBoost = 0.05f,
                                                        enableLens = false
                                                )
                                                .clip(CircleShape)
                                                .background(selectedGlassOverlayColor),
                                color = Color.Transparent,
                                shape = CircleShape
                        ) {
                                IconButton(
                                        onClick = {
                                                onScreenSelected(
                                                        ScreenRouteRegistry.defaultScreenForNavItem(item)
                                                )
                                        }
                                ) {
                                        Icon(
                                                imageVector = item.icon,
                                                contentDescription = stringResource(id = item.titleResId),
                                                tint =
                                                        if (selectedItem == item) {
                                                                if (appearance.buttonLiquidGlassEnabled) {
                                                                        appearance.selectedContentColor
                                                                } else {
                                                                        appearance.titleColor
                                                                }
                                                        } else {
                                                                appearance.itemColor
                                                        },
                                                modifier = Modifier.size(24.dp)
                                        )
                                }
                        }
                }

                if (pluginEntries.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(
                                modifier = Modifier.fillMaxWidth(0.45f),
                                color = appearance.dividerColor
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        pluginEntries.forEach { entry ->
                                val selectedGlassOverlayColor =
                                        if (selectedRouteId == entry.routeId) {
                                                appearance.selectedContainerColor.copy(alpha = 0.18f)
                                        } else {
                                                Color.Transparent
                                        }
                                Surface(
                                        modifier =
                                                Modifier.padding(vertical = 8.dp)
                                                        .size(44.dp)
                                                        .liquidGlass(
                                                                enabled = appearance.buttonLiquidGlassEnabled,
                                                                shape = CircleShape,
                                                                containerColor = appearance.buttonContainerColor,
                                                                shadowElevation =
                                                                        if (selectedRouteId == entry.routeId) 6.dp else 5.dp,
                                                                borderWidth = 0.5.dp,
                                                                blurRadius = 14.dp,
                                                                overlayAlphaBoost = 0.05f,
                                                                enableLens = false
                                                        )
                                                        .clip(CircleShape)
                                                        .background(selectedGlassOverlayColor),
                                        color = Color.Transparent,
                                        shape = CircleShape
                                ) {
                                        IconButton(onClick = { onNavigationEntrySelected(entry) }) {
                                                Icon(
                                                        imageVector = entry.icon,
                                                        contentDescription = entry.title,
                                                        tint =
                                                                if (selectedRouteId == entry.routeId) {
                                                                        if (appearance.buttonLiquidGlassEnabled) {
                                                                                appearance.selectedContentColor
                                                                        } else {
                                                                                appearance.titleColor
                                                                        }
                                                                } else {
                                                                        appearance.itemColor
                                                                },
                                                        modifier = Modifier.size(24.dp)
                                                )
                                        }
                                }
                        }
                }

                Spacer(modifier = Modifier.height(16.dp))
        }
}

@Composable
private fun NewSidebarTopContent(
        selectedItem: NavItem?,
        pluginEntries: List<NavigationEntrySpec>,
        selectedRouteId: String,
        brandName: String,
        appearance: NavigationDrawerAppearance,
        navItems: List<NavItem>,
        onNavItemClick: (NavItem) -> Unit,
        onNavigationEntryClick: (NavigationEntrySpec) -> Unit,
        onNewChat: () -> Unit,
) {
        // ── iOS 化：删除 Operit 的品牌卡与三张统计快捷键 ─────────────────
        // 原来的「Operit AI + WiFi 胶囊 + 包管理/权限/工作流 三卡」是 Operit 最强的识别特征：
        // 品牌卡 + 徽章数字 + 权限状态，都在 iOS 的抽屉里不会出现。
        // 现在只保留一行大标题（对应 iOS 大标题），然后直接是导航列表。
        // 品牌行照 cccodroid 的 .side-head：图标 22dp + 名字 15sp/600，
        // 一行结束，不放大标题，也不挂网络状态。
        // 原来这里是 titleLarge+Bold 的"Operit AI"再跟一行"WiFi"——大标题 + 状态行
        // 是 Operit 抽屉的辨识特征，而且 WiFi 状态对"打开抽屉找会话"这件事毫无帮助。
        Spacer(modifier = Modifier.height(14.dp))

        Text(
                text = brandName,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
                color = appearance.titleColor,
                maxLines = 1,
                modifier = Modifier.padding(start = 16.dp, end = 20.dp)
        )

        Spacer(modifier = Modifier.height(14.dp))

        // ── 「新对话」按钮（照 cccodroid 的 .side-new）─────────────────
        // cccodroid 抽屉的第一个可点项永远是"新对话"：全宽、1dp 描边、11dp 圆角、
        // 主色图标 + 文字。Operit 原来没有这个入口（之前藏在被删掉的第二条工具条里）。
        DrawerNewChatButton(
            appearance = appearance,
            onClick = onNewChat,
        )

        Spacer(modifier = Modifier.height(8.dp))

        navItems.forEach { item ->
                CompactNavigationDrawerItem(
                        icon = item.icon,
                        label = stringResource(id = item.titleResId),
                        selected = selectedItem == item,
                        appearance = appearance,
                        onClick = { onNavItemClick(item) }
                )
        }
        if (pluginEntries.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                // 分组标签改用 iOS 的小号大写字距样式
                Text(
                        text = stringResource(id = R.string.nav_group_plugins),
                        style = MaterialTheme.typography.labelSmall,
                        color = appearance.titleColor.copy(alpha = 0.55f),
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 20.dp, top = 8.dp, bottom = 4.dp)
                )
                pluginEntries.forEach { entry ->
                        CompactNavigationDrawerItem(
                                icon = entry.icon,
                                label = entry.title,
                                selected = selectedRouteId == entry.routeId,
                                appearance = appearance,
                                onClick = { onNavigationEntryClick(entry) }
                        )
                }
        }
}



@Composable
private fun SidebarQuickActionBadge(
        text: String,
        appearance: NavigationDrawerAppearance,
        selected: Boolean,
        modifier: Modifier = Modifier
) {
        Surface(
                modifier = modifier,
                shape = RoundedCornerShape(50),
                color =
                        if (selected) {
                                appearance.selectedContentColor.copy(alpha = 0.16f)
                        } else {
                                appearance.selectedContainerColor.copy(alpha = 0.18f)
                        }
        ) {
                Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall.copy(
                                letterSpacing = 0.3.sp
                        ),
                        color =
                                if (selected) appearance.selectedContentColor
                                else appearance.titleColor.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                )
        }
}

@Composable
private fun DrawerBottomShortcutRow(
        selectedItem: NavItem?,
        appearance: NavigationDrawerAppearance,
        onNavItemClick: (NavItem) -> Unit
) {
        // 底部账户行（照 cccodroid 的 .side-acct）：
        // 一行高 48dp、无底色、无玻璃、上方一条发丝线，图标 + 文字 + 右侧 chevron。
        // 原来是一块 68dp 高的"液态玻璃"整宽按钮，视觉重量比会话列表还重——
        // 底部这一条在 iOS/cccodroid 里是"最弱"的一层，不该抢戏。
        Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(
                        thickness = 0.5.dp,
                        color = appearance.dividerColor.copy(alpha = 0.5f)
                )
                Row(
                        modifier =
                                Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onNavItemClick(NavItem.Settings) }
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                        Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = appearance.titleColor.copy(alpha = 0.75f),
                                modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                                text = stringResource(id = NavItem.Settings.titleResId),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = appearance.titleColor.copy(alpha = 0.9f),
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                        )
                        Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = appearance.titleColor.copy(alpha = 0.35f),
                                modifier = Modifier.size(16.dp)
                        )
                }
        }
}



/**
 * 「新对话」按钮（照 cccodroid 的 `.side-new` 移植）。
 *
 * cccodroid 的写法：全宽、1dp 描边、11dp 圆角、padding 9×12、主色图标 18dp + 文字、间距 10dp。
 * 它是抽屉里第一个可点项，位置在品牌行之下、导航项之上——用户打开抽屉第一眼就能开新会话。
 */
@Composable
private fun DrawerNewChatButton(
    appearance: NavigationDrawerAppearance,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(11.dp)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .clip(shape)
                .border(width = 1.dp, color = appearance.dividerColor.copy(alpha = 0.55f), shape = shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = appearance.selectedContentColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "新对话",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = appearance.titleColor,
            maxLines = 1
        )
    }
}



/**
 * 「最近对话」列表（照 cccodroid 的 `.side-recent` 移植）。
 *
 * 这是 cccodroid 抽屉与 Operit 抽屉最大的结构差异：
 * Operit 的抽屉只有导航项，**没有会话列表**，切换会话要靠聊天页顶部另开一个选择器；
 * cccodroid 把会话列表直接放在抽屉里，占了剩余全部高度，点一下即切换。
 *
 * 形态对齐 `.side-recent{flex:1;overflow-y:auto}`：
 * - 标题一行小字（labelSmall + 字距）
 * - 每行：标题单行省略 + 时间戳右对齐弱化
 * - 当前会话高亮（主色文字 + 极浅底色）
 */
@Composable
private fun DrawerRecentChats(
    chats: List<ChatHistory>,
    currentChatId: String?,
    appearance: NavigationDrawerAppearance,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chats.isEmpty()) {
        return
    }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "最近对话",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = appearance.titleColor.copy(alpha = 0.55f),
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp)
        )
        chats.forEach { chat ->
            val selected = chat.id == currentChatId
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) {
                                appearance.selectedContainerColor.copy(alpha = 0.28f)
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            }
                        )
                        .clickable { onSelect(chat.id) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = chat.title.ifBlank { "未命名对话" },
                    fontSize = 13.5.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color =
                        if (selected) {
                            appearance.selectedContentColor
                        } else {
                            appearance.titleColor.copy(alpha = 0.85f)
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 时间戳（cccodroid 的 .side-recent 每行右侧有弱化时间）
                Text(
                    text = formatChatTimestamp(chat.updatedAt),
                    fontSize = 10.5.sp,
                    color = appearance.titleColor.copy(alpha = 0.45f),
                    maxLines = 1
                )
            }
        }
    }
}

/** 会话时间戳：今天显示 HH:mm，昨天显示「昨天」，更早显示 M-d。 */
private fun formatChatTimestamp(updatedAt: java.time.LocalDateTime): String {
    val today = java.time.LocalDate.now()
    val date = updatedAt.toLocalDate()
    return when (date) {
        today -> String.format(java.util.Locale.US, "%02d:%02d", updatedAt.hour, updatedAt.minute)
        today.minusDays(1) -> "昨天"
        else -> (updatedAt.monthValue).toString() + "-" + (updatedAt.dayOfMonth).toString()
    }
}

