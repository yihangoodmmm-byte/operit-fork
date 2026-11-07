package com.ai.assistance.operit.ui.main.components

import androidx.compose.foundation.background
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
                Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                ) {
                        NewSidebarTopContent(
                                selectedItem = selectedItem,
                                pluginEntries = pluginEntries,
                                selectedRouteId = selectedRouteId,
                                brandName = drawerBrandName,
                                isNetworkAvailable = isNetworkAvailable,
                                networkType = networkType,
                                appearance = appearance,
                                navItems = primaryNavItems,
                                onNavItemClick = handleNavItemClick,
                                onNavigationEntryClick = handleNavigationEntryClick
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                }

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
        isNetworkAvailable: Boolean,
        networkType: String,
        appearance: NavigationDrawerAppearance,
        navItems: List<NavItem>,
        onNavItemClick: (NavItem) -> Unit,
        onNavigationEntryClick: (NavigationEntrySpec) -> Unit
) {
        // ── iOS 化：删除 Operit 的品牌卡与三张统计快捷键 ─────────────────
        // 原来的「Operit AI + WiFi 胶囊 + 包管理/权限/工作流 三卡」是 Operit 最强的识别特征：
        // 品牌卡 + 徽章数字 + 权限状态，都在 iOS 的抽屉里不会出现。
        // 现在只保留一行大标题（对应 iOS 大标题），然后直接是导航列表。
        Spacer(modifier = Modifier.height(20.dp))

        Text(
                text = brandName,
                style = MaterialTheme.typography.titleLarge,
                color = appearance.titleColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, end = 20.dp, bottom = 8.dp)
        )

        // 网络状态降级为一行小字，不再是带底色的胶囊/卡片
        Text(
                text = if (isNetworkAvailable) networkType else stringResource(id = R.string.network_status_label),
                style = MaterialTheme.typography.labelSmall,
                color = appearance.titleColor.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 16.dp, end = 20.dp, bottom = 4.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

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
        // iOS 化：底部只保留「设置」一项，关于/使用手册从抽屉移除
        // （设置页内仍有「关于」入口，功能没有丢失）。
        Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
        ) {
                BottomShortcutDrawerItem(
                        modifier = Modifier.weight(1f),
                        item = NavItem.Settings,
                        selected = selectedItem == NavItem.Settings,
                        appearance = appearance,
                        onClick = { onNavItemClick(NavItem.Settings) }
                )
        }
}

@Composable
private fun BottomShortcutDrawerItem(
        modifier: Modifier = Modifier,
        item: NavItem,
        selected: Boolean,
        appearance: NavigationDrawerAppearance,
        onClick: () -> Unit
) {
        val itemShape = RoundedCornerShape(14.dp)
        val selectedGlassOverlayColor =
                if (selected) {
                        appearance.selectedContainerColor.copy(alpha = 0.18f)
                } else {
                        Color.Transparent
                }
        val accentColor = appearance.selectedContentColor

        Surface(
                modifier =
                        modifier
                                .height(68.dp)
                                .liquidGlass(
                                        enabled = appearance.buttonLiquidGlassEnabled,
                                        shape = itemShape,
                                        containerColor = appearance.buttonContainerColor,
                                        shadowElevation = if (selected) 6.dp else 4.dp,
                                        borderWidth = 0.5.dp,
                                        blurRadius = 12.dp,
                                        overlayAlphaBoost = 0.04f,
                                        enableLens = false
                                )
                                .clip(itemShape)
                                .background(selectedGlassOverlayColor),
                onClick = onClick,
                color =
                        if (appearance.buttonLiquidGlassEnabled) {
                                Color.Transparent
                        } else if (selected) {
                                appearance.selectedContainerColor
                        } else {
                                Color.Transparent
                        },
                shape = itemShape
        ) {
                Box(modifier = Modifier.fillMaxSize()) {
                        if (selected) {
                                Box(
                                        modifier = Modifier
                                                .align(Alignment.TopCenter)
                                                .fillMaxWidth(0.4f)
                                                .height(2.5.dp)
                                                .padding(top = 4.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(accentColor.copy(alpha = 0.7f))
                                )
                        }
                        Column(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                        ) {
                                Icon(
                                        imageVector = item.icon,
                                        contentDescription = null,
                                        tint =
                                                if (selected) appearance.selectedContentColor
                                                else appearance.itemColor,
                                        modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.height(5.dp))
                                Text(
                                        text = stringResource(id = item.titleResId),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                                letterSpacing = 0.1.sp
                                        ),
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        textAlign = TextAlign.Center,
                                        color =
                                                if (selected) appearance.selectedContentColor
                                                else appearance.itemColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth()
                                )
                        }
                }
        }
}
