package app.fintrack.ui.navigation

import androidx.compose.ui.unit.sp
import app.fintrack.ui.components.FitText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.fintrack.ui.accounts.AccountEditScreen
import app.fintrack.ui.accounts.AccountsScreen
import app.fintrack.ui.budget.BudgetScreen
import app.fintrack.ui.categories.CategoriesScreen
import app.fintrack.ui.categories.CategoryEditScreen
import app.fintrack.ui.components.Motion
import app.fintrack.ui.components.bounceClick
import app.fintrack.ui.components.lighten
import app.fintrack.ui.components.pressScale
import app.fintrack.ui.editor.EditorScreen
import app.fintrack.ui.history.HistoryScreen
import app.fintrack.ui.home.HomeScreen
import app.fintrack.notify.RecurringNotifier
import app.fintrack.ui.loans.LoanDetailScreen
import app.fintrack.ui.recurring.RecurringScreen
import app.fintrack.ui.loans.LoanEditScreen
import app.fintrack.ui.loans.LoansScreen
import app.fintrack.ui.piggy.PiggyDetailScreen
import app.fintrack.ui.piggy.PiggyEditScreen
import app.fintrack.ui.settings.SettingsScreen
import app.fintrack.ui.stats.StatisticsScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/** Пять слотов панели; null — центральная кнопка «+». */
private val slots: List<Tab?> = listOf(
    Tab(Routes.HOME, "Главная", Icons.Outlined.Home, Icons.Rounded.Home),
    Tab(Routes.HISTORY, "История", Icons.Outlined.ReceiptLong, Icons.Rounded.ReceiptLong),
    null,
    Tab(Routes.STATS, "Статистика", Icons.Outlined.PieChart, Icons.Rounded.PieChart),
    Tab(Routes.BUDGET, "Бюджет", Icons.Outlined.Savings, Icons.Rounded.Savings),
)

private fun optionalString(name: String) = navArgument(name) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

private fun isTab(route: String?) = route in Routes.tabs

@Composable
fun FinTrackNavHost(openRequest: String? = null, onOpenHandled: () -> Unit = {}) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    // Нажатие на уведомление о регулярном платеже открывает их список.
    androidx.compose.runtime.LaunchedEffect(openRequest) {
        if (openRequest == RecurringNotifier.OPEN_RECURRING) {
            nav.navigate(Routes.RECURRING) { launchSingleTop = true }
            onOpenHandled()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = isTab(route),
                enter = slideInVertically(tween(Motion.MEDIUM_MS)) { it } + fadeIn(tween(Motion.MEDIUM_MS)),
                exit = slideOutVertically(tween(Motion.SHORT_MS)) { it } + fadeOut(tween(Motion.SHORT_MS)),
            ) {
                BottomBar(
                    current = route,
                    onTab = { nav.navigateToTab(it) },
                    onAdd = { nav.navigate(Routes.editor()) },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
            // Вкладки сменяются мягким «растворением», вложенные экраны въезжают сбоку.
            enterTransition = {
                if (isTab(initialState.destination.route) && isTab(targetState.destination.route)) {
                    fadeIn(tween(Motion.MEDIUM_MS, delayMillis = 60)) + scaleIn(tween(Motion.MEDIUM_MS, delayMillis = 60), initialScale = 0.97f)
                } else {
                    slideInHorizontally(tween(Motion.LONG_MS)) { it / 3 } + fadeIn(tween(Motion.MEDIUM_MS))
                }
            },
            exitTransition = {
                if (isTab(initialState.destination.route) && isTab(targetState.destination.route)) {
                    fadeOut(tween(90))
                } else {
                    slideOutHorizontally(tween(Motion.LONG_MS)) { -it / 8 } + fadeOut(tween(Motion.MEDIUM_MS))
                }
            },
            popEnterTransition = {
                slideInHorizontally(tween(Motion.LONG_MS)) { -it / 8 } + fadeIn(tween(Motion.MEDIUM_MS))
            },
            popExitTransition = {
                slideOutHorizontally(tween(Motion.MEDIUM_MS)) { it / 3 } + fadeOut(tween(Motion.SHORT_MS))
            },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onAdd = { type -> nav.navigate(Routes.editor(type = type)) },
                    onOpenTransaction = { id -> nav.navigate(Routes.editor(id = id)) },
                    onOpenAccounts = { nav.navigate(Routes.ACCOUNTS) },
                    onOpenHistory = { nav.navigateToTab(Routes.HISTORY) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onOpenBudget = { nav.navigateToTab(Routes.BUDGET) },
                    onOpenPiggy = { id -> nav.navigate(Routes.piggy(id)) },
                    onNewPiggy = { nav.navigate(Routes.piggyEdit()) },
                    onOpenLoans = { nav.navigate(Routes.LOANS) },
                    onOpenLoan = { id -> nav.navigate(Routes.loan(id)) },
                    onOpenRecurring = { nav.navigate(Routes.RECURRING) },
                    onOpenRecurringItem = { id -> nav.navigate(Routes.recurringEdit(id)) },
                )
            }
            composable(Routes.STATS) { StatisticsScreen() }
            composable(Routes.BUDGET) { BudgetScreen() }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    onOpenTransaction = { id -> nav.navigate(Routes.editor(id = id)) },
                    onBack = null,
                )
            }
            composable(
                Routes.ACCOUNT_HISTORY,
                arguments = listOf(navArgument("accountId") { type = NavType.StringType }),
            ) {
                HistoryScreen(
                    onOpenTransaction = { id -> nav.navigate(Routes.editor(id = id)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.ACCOUNTS) {
                AccountsScreen(
                    onBack = { nav.popBackStack() },
                    onAddAccount = { nav.navigate(Routes.accountEdit()) },
                    onOpenAccount = { id -> nav.navigate(Routes.accountEdit(id)) },
                    onOpenHistory = { id -> nav.navigate(Routes.accountHistory(id)) },
                    onOpenPiggy = { id -> nav.navigate(Routes.piggy(id)) },
                    onOpenLoan = { id -> nav.navigate(Routes.loan(id)) },
                    onOpenLoans = { nav.navigate(Routes.LOANS) },
                )
            }
            composable(Routes.RECURRING) {
                RecurringScreen(
                    onBack = { nav.popBackStack() },
                    onOpen = { id -> nav.navigate(Routes.recurringEdit(id)) },
                    onNew = { nav.navigate(Routes.recurringNew()) },
                )
            }
            composable(Routes.LOANS) {
                LoansScreen(
                    onBack = { nav.popBackStack() },
                    onOpenLoan = { id -> nav.navigate(Routes.loan(id)) },
                    onNewLoan = { kind -> nav.navigate(Routes.loanEdit(kind = kind)) },
                )
            }
            composable(Routes.LOAN, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                LoanDetailScreen(
                    onBack = { nav.popBackStack() },
                    onEdit = { id -> nav.navigate(Routes.loanEdit(id)) },
                    onOpenTransaction = { id -> nav.navigate(Routes.editor(id = id)) },
                    onAddAccount = { nav.navigate(Routes.accountEdit()) },
                )
            }
            composable(Routes.LOAN_EDIT, arguments = listOf(optionalString("id"), optionalString("kind"))) {
                LoanEditScreen(
                    onClose = { nav.popBackStack() },
                    onCreated = { id ->
                        nav.navigate(Routes.loan(id)) { popUpTo(Routes.LOAN_EDIT) { inclusive = true } }
                    },
                )
            }
            composable(Routes.PIGGY, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                PiggyDetailScreen(
                    onBack = { nav.popBackStack() },
                    onEdit = { id -> nav.navigate(Routes.piggyEdit(id)) },
                    onOpenTransaction = { id -> nav.navigate(Routes.editor(id = id)) },
                    onAddAccount = { nav.navigate(Routes.accountEdit()) },
                )
            }
            composable(Routes.PIGGY_EDIT, arguments = listOf(optionalString("id"))) {
                PiggyEditScreen(
                    onClose = { nav.popBackStack() },
                    onCreated = { id ->
                        nav.navigate(Routes.piggy(id)) { popUpTo(Routes.PIGGY_EDIT) { inclusive = true } }
                    },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenCategories = { nav.navigate(Routes.CATEGORIES) },
                    onOpenAccounts = { nav.navigate(Routes.ACCOUNTS) },
                    onOpenLoans = { nav.navigate(Routes.LOANS) },
                    onOpenRecurring = { nav.navigate(Routes.RECURRING) },
                )
            }
            composable(
                Routes.EDITOR,
                arguments = listOf(
                    optionalString("type"),
                    optionalString("id"),
                    optionalString("copyOf"),
                    optionalString("recurringId"),
                    optionalString("repeat"),
                ),
            ) {
                EditorScreen(
                    onClose = { nav.popBackStack() },
                    onCopy = { id -> nav.navigate(Routes.editor(copyOf = id)) { popUpTo(Routes.EDITOR) { inclusive = true } } },
                    onAddCategory = { kind -> nav.navigate(Routes.categoryEdit(kind = kind)) },
                )
            }
            composable(Routes.ACCOUNT_EDIT, arguments = listOf(optionalString("id"))) {
                AccountEditScreen(
                    onClose = { nav.popBackStack() },
                    onOpenHistory = { id -> nav.navigate(Routes.accountHistory(id)) },
                )
            }
            composable(Routes.CATEGORIES) {
                CategoriesScreen(
                    onBack = { nav.popBackStack() },
                    onOpenCategory = { id -> nav.navigate(Routes.categoryEdit(id = id)) },
                    onAddCategory = { kind, parentId -> nav.navigate(Routes.categoryEdit(kind = kind, parentId = parentId)) },
                )
            }
            composable(
                Routes.CATEGORY_EDIT,
                arguments = listOf(optionalString("id"), optionalString("kind"), optionalString("parentId")),
            ) {
                CategoryEditScreen(onClose = { nav.popBackStack() })
            }
        }
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private val IndicatorWidth = 60.dp
private val IndicatorHeight = 32.dp

/**
 * Нижняя панель: «пилюля»-индикатор плавно перетекает между вкладками на пружине,
 * иконка выбранной вкладки заливается и слегка подпрыгивает.
 */
@Composable
private fun BottomBar(current: String?, onTab: (String) -> Unit, onAdd: () -> Unit) {
    val selectedIndex = slots.indexOfFirst { it?.route == current }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(72.dp),
            ) {
                val slotWidth = maxWidth / slots.size
                val target = slotWidth * selectedIndex.coerceAtLeast(0) + (slotWidth - IndicatorWidth) / 2
                val indicatorX by animateDpAsState(target, Motion.indicator(), label = "tabIndicator")
                val indicatorAlpha by animateFloatAsState(if (selectedIndex >= 0) 1f else 0f, label = "tabIndicatorAlpha")
                val density = LocalDensity.current

                Box(
                    Modifier
                        .offset { IntOffset(with(density) { indicatorX.roundToPx() }, with(density) { 10.dp.roundToPx() }) }
                        .size(IndicatorWidth, IndicatorHeight)
                        .graphicsLayer { alpha = indicatorAlpha }
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                )

                Row(Modifier.fillMaxSize()) {
                    slots.forEach { tab ->
                        if (tab == null) {
                            AddButton(onAdd)
                        } else {
                            TabItem(tab, selected = tab.route == current) { onTab(tab.route) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.TabItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(Motion.MEDIUM_MS),
        label = "tabColor",
    )
    val iconScale by animateFloatAsState(if (selected) 1.1f else 1f, Motion.bouncy(), label = "tabIconScale")
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = source,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .pressScale(source, 0.9f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(top = 10.dp).size(IndicatorWidth, IndicatorHeight), contentAlignment = Alignment.Center) {
            Icon(
                if (selected) tab.selectedIcon else tab.icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
            )
        }
        Spacer(Modifier.height(4.dp))
        FitText(
            tab.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = color,
            minFontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun RowScope.AddButton(onAdd: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .shadow(10.dp, CircleShape, ambientColor = primary, spotColor = primary)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(primary.lighten(0.15f), primary)))
                .bounceClick(pressedScale = 0.88f, onClickLabel = "Добавить операцию", onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = "Добавить операцию",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}
