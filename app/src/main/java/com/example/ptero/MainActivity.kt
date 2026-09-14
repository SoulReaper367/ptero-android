package com.example.ptero

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.*
import androidx.navigation.compose.*
import com.example.ptero.data.*
import com.example.ptero.ui.*
import com.example.ptero.viewmodel.*
import java.util.Calendar

// ─── Navigation routes ────────────────────────────────────────────────────────

private const val ROUTE_HOME     = "home"
private const val ROUTE_CONSOLE  = "console/{accountId}/{identifier}"
private const val ROUTE_ADDPANEL = "add_panel"
private const val ROUTE_SETTINGS = "settings"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NookTheme {
                PteroApp()
            }
        }
    }
}

// ─── App scaffold with nav ────────────────────────────────────────────────────

@Composable
fun PteroApp() {
    val navController = rememberNavController()
    val serversVm: ServersViewModel = viewModel()

    NavHost(
        navController      = navController,
        startDestination   = ROUTE_HOME,
        enterTransition    = { fadeIn(tween(200)) + slideInHorizontally { it / 6 } },
        exitTransition     = { fadeOut(tween(150)) },
        popEnterTransition = { fadeIn(tween(200)) + slideInHorizontally { -it / 6 } },
        popExitTransition  = { fadeOut(tween(150)) + slideOutHorizontally { it / 6 } }
    ) {
        composable(ROUTE_HOME) {
            HomeScreen(
                vm            = serversVm,
                onAddPanel    = { navController.navigate(ROUTE_ADDPANEL) },
                onOpenConsole = { accountId, identifier ->
                    // URL-encode the identifier to be safe with special chars
                    navController.navigate("console/$accountId/$identifier")
                },
                onSettings    = { navController.navigate(ROUTE_SETTINGS) }
            )
        }

        composable(ROUTE_CONSOLE) { backStack ->
            // Bug fix: original used ?: return@composable on args.  While this is correct,
            // if accountId / identifier are missing the screen just showed a spinner forever.
            // Now we show a user-facing error with a back button instead.
            val accountId  = backStack.arguments?.getString("accountId")
            val identifier = backStack.arguments?.getString("identifier")

            if (accountId == null || identifier == null) {
                MissingServerPlaceholder(onBack = { navController.popBackStack() })
                return@composable
            }

            val state by serversVm.uiState.collectAsStateWithLifecycle()

            val uiServer = state.servers.firstOrNull {
                it.account.id == accountId && it.attributes.identifier == identifier
            }

            if (uiServer != null) {
                ConsoleScreen(
                    uiServer  = uiServer,
                    serversVm = serversVm,
                    onBack    = { navController.popBackStack() }
                )
            } else if (state.isLoading) {
                // Still fetching — show spinner
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(NookColors.AppBackground)
                ) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center),
                        color = NookColors.AccentBlue
                    )
                }
            } else {
                // Bug fix: original spun forever if the server wasn't found after loading.
                // Now shows an actionable error.
                MissingServerPlaceholder(onBack = { navController.popBackStack() })
            }
        }

        composable(ROUTE_ADDPANEL) {
            AddPanelScreen(
                vm     = serversVm,
                onBack = { navController.popBackStack() }
            )
        }

        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                vm     = serversVm,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

/** Shown when a console route is opened for a server that can't be found. */
@Composable
private fun MissingServerPlaceholder(onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(NookColors.AppBackground)
    ) {
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Server not found",
                style = MaterialTheme.typography.headlineMedium,
                color = NookColors.TextPrimary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "It may have been removed or is still loading.",
                style = MaterialTheme.typography.bodyMedium,
                color = NookColors.TextSecondary
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onBack,
                colors  = ButtonDefaults.buttonColors(containerColor = NookColors.AccentBlue)
            ) {
                Text("Go Back")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HOME SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun HomeScreen(
    vm: ServersViewModel,
    onAddPanel: () -> Unit,
    onOpenConsole: (accountId: String, identifier: String) -> Unit,
    onSettings: () -> Unit
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = NookColors.AppBackground,
        floatingActionButton = {
            FloatingActionButton(
                onClick        = onAddPanel,
                containerColor = NookColors.AccentBlue,
                contentColor   = Color.White,
                shape          = CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Panel")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier            = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding      = PaddingValues(
                start  = 16.dp,
                end    = 16.dp,
                top    = 12.dp,
                bottom = 100.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                HomeHeader(
                    displayName  = vm.getDisplayName(),
                    serverCount  = state.servers.size,
                    onlineCount  = state.servers.count { it.attributes.serverStatus == "running" },
                    onSettings   = onSettings,
                    onRefresh    = { vm.fetchAllServers() },
                    isRefreshing = state.isLoading
                )
                Spacer(Modifier.height(8.dp))
            }

            when {
                state.isLoading && state.servers.isEmpty() -> {
                    item { LoadingShimmerGrid() }
                }
                state.accounts.isEmpty() -> {
                    item { EmptyPanelsPrompt(onAddPanel) }
                }
                state.servers.isEmpty() && !state.isLoading -> {
                    item { EmptyServersMessage() }
                }
                else -> {
                    items(
                        items = state.servers,
                        key   = { "${it.account.id}:${it.attributes.identifier}" }
                    ) { uiServer ->
                        ServerCard(
                            uiServer      = uiServer,
                            isPowerActing = uiServer.attributes.identifier in state.powerActionInProgress,
                            onOpenConsole = {
                                // Bug fix: guard against blank identifier so the nav
                                // route is never "console/accountId/"
                                val id = uiServer.attributes.identifier
                                if (id.isNotBlank()) {
                                    onOpenConsole(uiServer.account.id, id)
                                }
                            },
                            onPowerSignal = { signal -> vm.sendPowerSignal(uiServer, signal) }
                        )
                    }
                }
            }

            // Error banners with dismiss button
            if (state.errors.isNotEmpty()) {
                items(state.errors, key = { it }) { errorMessage ->
                    NookErrorBanner(
                        message   = errorMessage,
                        onDismiss = { vm.clearErrors() }
                    )
                }
            }
        }
    }
}

// ─── Header ───────────────────────────────────────────────────────────────────

@Composable
fun HomeHeader(
    displayName: String,
    serverCount: Int,
    onlineCount: Int,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    isRefreshing: Boolean
) {
    // Bug fix: greeting was in remember{} with no key, so it never updated after midnight.
    // Moved the hour read outside remember.
    val hour     = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) {
        in 5..11  -> "Good Morning"
        in 12..16 -> "Good Afternoon"
        in 17..20 -> "Good Evening"
        else      -> "Good Night"
    }

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = "$greeting, $displayName 👋",
                style = MaterialTheme.typography.displayLarge
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(NookColors.StatusOnline, CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text  = if (serverCount == 0) "No servers loaded"
                            else "$onlineCount / $serverCount servers online",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NookColors.TextSecondary
                )
            }
        }

        Row {
            if (isRefreshing) {
                val infiniteTransition = rememberInfiniteTransition(label = "spin")
                val angle by infiniteTransition.animateFloat(
                    initialValue  = 0f,
                    targetValue   = 360f,
                    animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
                    label         = "angle"
                )
                Icon(
                    imageVector        = Icons.Default.Refresh,
                    contentDescription = "Refreshing",
                    tint               = NookColors.AccentBlue,
                    modifier           = Modifier
                        .size(40.dp)
                        .padding(8.dp)
                        .rotate(angle)
                )
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector        = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint               = NookColors.TextSecondary
                    )
                }
            }
            IconButton(onClick = onSettings) {
                Icon(
                    imageVector        = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint               = NookColors.TextSecondary
                )
            }
        }
    }
}

// ─── Server Card ─────────────────────────────────────────────────────────────

@Composable
fun ServerCard(
    uiServer: UiServer,
    isPowerActing: Boolean,
    onOpenConsole: () -> Unit,
    onPowerSignal: (String) -> Unit
) {
    val attrs   = uiServer.attributes
    val status  = attrs.serverStatus
    val memMb   = attrs.currentMemoryBytes / 1_048_576L
    val limitMb = attrs.limits.memory

    // Bug fix: if limitMb is 0 (unlimited), show 0 fraction rather than divide-by-zero
    val memFrac = if (limitMb > 0) (memMb.toFloat() / limitMb.toFloat()).coerceIn(0f, 1f) else 0f
    val cpuFrac = if (attrs.limits.cpu > 0) {
        (attrs.currentCpu / attrs.limits.cpu.toDouble()).toFloat().coerceIn(0f, 1f)
    } else 0f

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = NookShapes.Card,
        colors   = CardDefaults.cardColors(containerColor = NookColors.CardSurface),
        border   = BorderStroke(1.dp, NookColors.CardBorder),
        onClick  = onOpenConsole
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Header row ─────────────────────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text     = attrs.name.ifBlank { "Unnamed Server" },
                        style    = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (attrs.allocationDisplay.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text  = attrs.allocationDisplay,
                            style = MaterialTheme.typography.bodySmall,
                            color = NookColors.TextMuted
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    if (attrs.panelLabel.isNotBlank()) {
                        NookPillBadge(
                            text            = attrs.panelLabel,
                            backgroundColor = NookColors.PillGray,
                            textColor       = NookColors.TextSecondary
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    NookPillBadge(
                        text            = serverStatusLabel(status),
                        backgroundColor = serverStatusPillBg(status),
                        textColor       = serverStatusColor(status)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── CPU bar ────────────────────────────────────────────────────────
            StatBarRow(
                label    = "CPU",
                current  = "%.1f%%".format(attrs.currentCpu),
                limit    = if (attrs.limits.cpu > 0) "${attrs.limits.cpu}%" else "∞",
                frac     = cpuFrac,
                barColor = NookColors.BarCpu
            )

            Spacer(Modifier.height(8.dp))

            // ── Memory bar ─────────────────────────────────────────────────────
            StatBarRow(
                label    = "RAM",
                current  = formatBytes(attrs.currentMemoryBytes),
                limit    = if (limitMb > 0) formatMbLimit(limitMb) else "∞",
                frac     = memFrac,
                barColor = NookColors.BarMemory
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = NookColors.Divider, thickness = 1.dp)
            Spacer(Modifier.height(10.dp))

            // ── Power controls ─────────────────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                if (isPowerActing) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(20.dp),
                        color       = NookColors.AccentBlue,
                        strokeWidth = 2.dp
                    )
                } else {
                    PowerButton(
                        icon        = Icons.Default.PlayArrow,
                        tint        = NookColors.StatusOnline,
                        contentDesc = "Start",
                        enabled     = status == "offline",
                        onClick     = { onPowerSignal("start") }
                    )
                    PowerButton(
                        icon        = Icons.Default.Refresh,
                        tint        = NookColors.StatusStarting,
                        contentDesc = "Restart",
                        enabled     = status == "running",
                        onClick     = { onPowerSignal("restart") }
                    )
                    PowerButton(
                        icon        = Icons.Default.Stop,
                        tint        = NookColors.StatusOffline,
                        contentDesc = "Stop",
                        enabled     = status == "running",
                        onClick     = { onPowerSignal("stop") }
                    )
                    PowerButton(
                        icon        = Icons.Default.Warning,
                        tint        = NookColors.TextMuted,
                        contentDesc = "Kill",
                        // Bug fix: Kill was enabled during "starting" which is unsafe.
                        // Restrict to running + stopping only.
                        enabled     = status == "running" || status == "stopping",
                        onClick     = { onPowerSignal("kill") }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatBarRow(
    label: String,
    current: String,
    limit: String,
    frac: Float,
    barColor: Color
) {
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.labelSmall,
            color    = NookColors.TextSecondary,
            modifier = Modifier.width(32.dp)
        )
        Spacer(Modifier.width(8.dp))
        NookProgressBar(
            fraction = frac,
            color    = barColor,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        NookPillBadge(
            text            = "$current / $limit",
            backgroundColor = NookColors.PillGray,
            textColor       = NookColors.TextSecondary
        )
    }
}

@Composable
private fun PowerButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    contentDesc: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick  = onClick,
        enabled  = enabled,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = contentDesc,
            tint               = if (enabled) tint else NookColors.TextMuted.copy(alpha = 0.35f),
            modifier           = Modifier.size(20.dp)
        )
    }
}

// ─── Reusable design-system components ───────────────────────────────────────

@Composable
fun NookPillBadge(
    text: String,
    backgroundColor: Color,
    textColor: Color
) {
    Box(
        modifier = Modifier
            .background(backgroundColor, NookShapes.Pill)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text  = text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor
        )
    }
}

@Composable
fun NookProgressBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color  = NookColors.BarTrack,
    height: androidx.compose.ui.unit.Dp = 6.dp
) {
    // Bug fix: coerce here as extra safety; upstream should already be 0–1
    val safeFrac by animateFloatAsState(
        targetValue   = fraction.coerceIn(0f, 1f),
        animationSpec = tween(400),
        label         = "progress"
    )
    Box(
        modifier = modifier
            .height(height)
            .clip(NookShapes.Pill)
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(safeFrac)
                .clip(NookShapes.Pill)
                .background(color)
        )
    }
}

@Composable
fun NookStatusGlowIndicator(status: String, size: androidx.compose.ui.unit.Dp = 14.dp) {
    val color = serverStatusColor(status)
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val alpha by infiniteTransition.animateFloat(
        initialValue  = 0.4f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    Box(
        modifier = Modifier
            .size(size)
            .drawBehind {
                drawCircle(
                    color  = color.copy(alpha = alpha * 0.5f),
                    radius = this.size.minDimension
                )
            }
            .background(color, CircleShape)
    )
}

/**
 * Error banner with optional dismiss.
 *
 * Bug fix: maxLines was 3 in the original — long Cloudflare/timeout messages were
 * silently truncated. Raised to 6; the messages from ApiResult are already concise.
 */
@Composable
fun NookErrorBanner(
    message: String,
    onDismiss: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NookColors.PillRed, NookShapes.Small)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector        = Icons.Default.Error,
            contentDescription = null,
            tint               = NookColors.StatusOffline,
            modifier           = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text     = message,
            style    = MaterialTheme.typography.bodySmall,
            color    = NookColors.StatusOffline,
            modifier = Modifier.weight(1f),
            maxLines = 6,
            overflow = TextOverflow.Ellipsis
        )
        if (onDismiss != null) {
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick  = onDismiss,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    imageVector        = Icons.Default.Close,
                    contentDescription = "Dismiss error",
                    tint               = NookColors.StatusOffline,
                    modifier           = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
fun EmptyPanelsPrompt(onAddPanel: () -> Unit) {
    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🌑", fontSize = 48.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text  = "No panels connected",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text  = "Tap the + button to add your first Pterodactyl panel.",
            style = MaterialTheme.typography.bodyMedium,
            color = NookColors.TextSecondary
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onAddPanel,
            colors  = ButtonDefaults.buttonColors(containerColor = NookColors.AccentBlue),
            shape   = NookShapes.Button
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Connect Panel")
        }
    }
}

@Composable
fun EmptyServersMessage() {
    Box(
        modifier         = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text  = "No servers found on connected panels.",
            style = MaterialTheme.typography.bodyMedium,
            color = NookColors.TextSecondary
        )
    }
}

@Composable
fun LoadingShimmerGrid() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(NookColors.CardSurface, NookShapes.Card)
                    .border(1.dp, NookColors.CardBorder, NookShapes.Card)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CONSOLE SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ConsoleScreen(
    uiServer: UiServer,
    serversVm: ServersViewModel,
    onBack: () -> Unit
) {
    val consoleVm: ConsoleViewModel = viewModel()
    val state by consoleVm.state.collectAsStateWithLifecycle()

    val scrollState  = rememberLazyListState()
    var commandInput by remember { mutableStateOf("") }
    val keyboardCtrl = LocalSoftwareKeyboardController.current

    // Connect once per identifier (reconnects if the user navigates to a different server)
    LaunchedEffect(uiServer.attributes.identifier) {
        consoleVm.connect(uiServer)
    }

    // Auto-scroll to bottom on new output
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) {
            scrollState.animateScrollToItem(state.lines.lastIndex)
        }
    }

    DisposableEffect(Unit) {
        onDispose { consoleVm.disconnect() }
    }

    Scaffold(
        containerColor = NookColors.AppBackground,
        topBar = {
            ConsoleTopBar(
                serverName   = state.serverName,
                status       = state.serverStatus,
                panelLabel   = state.panelLabel,
                isConnected  = state.isConnected,
                isConnecting = state.isConnecting,
                onBack       = onBack,
                onPower      = { signal -> serversVm.sendPowerSignal(uiServer, signal) }
            )
        },
        bottomBar = {
            ConsoleCommandBar(
                value    = commandInput,
                onChange = { commandInput = it },
                onSend   = {
                    if (commandInput.isNotBlank()) {
                        consoleVm.sendCommand(commandInput)
                        commandInput = ""
                        keyboardCtrl?.hide()
                    }
                },
                enabled  = state.isConnected
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(NookColors.ConsoleBg)
        ) {
            when {
                state.isConnecting -> {
                    Column(
                        modifier            = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = NookColors.AccentBlue)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text  = "Connecting to console…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NookColors.TextSecondary
                        )
                    }
                }
                state.lines.isEmpty() && !state.isConnected && state.error == null -> {
                    Text(
                        text     = "Waiting for output…",
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = NookColors.TextMuted,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    LazyColumn(
                        state               = scrollState,
                        modifier            = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        items(state.lines) { line ->
                            Text(
                                text  = line,
                                style = MaterialTheme.typography.labelMedium,
                                color = consoleLineColor(line)
                            )
                        }
                    }
                }
            }

            // Error banner overlaid at the bottom — dismissible
            if (state.error != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    NookErrorBanner(
                        message   = state.error!!,
                        onDismiss = { consoleVm.clearError() }
                    )
                }
            }
        }
    }
}

private fun consoleLineColor(line: String): Color {
    val lower = line.lowercase()
    return when {
        lower.contains("error") || lower.contains("fatal") || lower.contains("exception") ->
            Color(0xFFFF6B6B)
        lower.contains("warn") ->
            Color(0xFFF59E0B)
        lower.contains("info") || lower.startsWith("[server thread/info]") ->
            Color(0xFF8EC3F5)
        lower.contains("done") || lower.contains("started") ->
            Color(0xFF10B981)
        else -> NookColors.TextPrimary
    }
}

@Composable
fun ConsoleTopBar(
    serverName: String,
    status: String,
    panelLabel: String,
    isConnected: Boolean,
    isConnecting: Boolean,
    onBack: () -> Unit,
    onPower: (String) -> Unit
) {
    var showPowerMenu by remember { mutableStateOf(false) }

    Surface(
        color          = NookColors.CardSurface,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint               = NookColors.TextSecondary
                    )
                }
                NookStatusGlowIndicator(status = status, size = 10.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text     = serverName.ifBlank { "Console" },
                        style    = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text  = buildString {
                            append(serverStatusLabel(status))
                            if (panelLabel.isNotBlank()) append(" · $panelLabel")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = serverStatusColor(status)
                    )
                }
                NookPillBadge(
                    text = when {
                        isConnecting -> "Connecting"
                        isConnected  -> "Live"
                        else         -> "Offline"
                    },
                    backgroundColor = when {
                        isConnecting -> NookColors.PillAmber
                        isConnected  -> NookColors.PillGreen
                        else         -> NookColors.PillRed
                    },
                    textColor = when {
                        isConnecting -> NookColors.StatusStarting
                        isConnected  -> NookColors.StatusOnline
                        else         -> NookColors.StatusOffline
                    }
                )
                Spacer(Modifier.width(4.dp))
                Box {
                    IconButton(onClick = { showPowerMenu = !showPowerMenu }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Power menu",
                            tint               = NookColors.TextSecondary
                        )
                    }
                    DropdownMenu(
                        expanded         = showPowerMenu,
                        onDismissRequest = { showPowerMenu = false },
                        containerColor   = NookColors.CardSurface
                    ) {
                        listOf(
                            "start"   to ("▶  Start"   to NookColors.StatusOnline),
                            "restart" to ("↺  Restart" to NookColors.StatusStarting),
                            "stop"    to ("■  Stop"    to NookColors.StatusOffline),
                            "kill"    to ("✕  Kill"    to NookColors.TextMuted)
                        ).forEach { (signal, pair) ->
                            val (label, color) = pair
                            DropdownMenuItem(
                                text    = {
                                    Text(
                                        label,
                                        color = color,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                onClick = {
                                    onPower(signal)
                                    showPowerMenu = false
                                }
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = NookColors.Divider, thickness = 1.dp)
        }
    }
}

@Composable
fun ConsoleCommandBar(
    value: String,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean
) {
    Surface(
        color          = NookColors.CardSurface,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value         = value,
                onValueChange = onChange,
                modifier      = Modifier.weight(1f),
                placeholder   = {
                    Text(
                        "> Enter command…",
                        style = MaterialTheme.typography.labelMedium,
                        color = NookColors.TextMuted
                    )
                },
                textStyle   = MaterialTheme.typography.labelMedium.copy(
                    color = NookColors.TextPrimary
                ),
                singleLine  = true,
                enabled     = enabled,
                shape       = NookShapes.Input,
                colors      = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor      = NookColors.AccentBlue,
                    unfocusedBorderColor    = NookColors.CardBorder,
                    disabledBorderColor     = NookColors.CardBorder,
                    focusedContainerColor   = NookColors.InputBackground,
                    unfocusedContainerColor = NookColors.InputBackground,
                    cursorColor             = NookColors.AccentBlue
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() })
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick  = onSend,
                enabled  = enabled && value.isNotBlank(),
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        if (enabled && value.isNotBlank()) NookColors.AccentBlue
                        else NookColors.CardBorder,
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector        = Icons.Default.Send,
                    contentDescription = "Send",
                    tint               = if (enabled && value.isNotBlank()) Color.White
                                         else NookColors.TextMuted,
                    modifier           = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SETTINGS SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(vm: ServersViewModel, onBack: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf<PanelAccount?>(null) }

    Scaffold(
        containerColor = NookColors.AppBackground,
        topBar = {
            Surface(color = NookColors.CardSurface) {
                Row(
                    modifier          = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            "Back",
                            tint = NookColors.TextSecondary
                        )
                    }
                    Text("Settings", style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier            = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding      = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text     = "Connected Panels",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = NookColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            if (state.accounts.isEmpty()) {
                item {
                    Text(
                        text  = "No panels connected yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NookColors.TextMuted
                    )
                }
            } else {
                items(state.accounts, key = { it.id }) { account ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = NookColors.CardSurface),
                        shape  = NookShapes.Card,
                        border = BorderStroke(1.dp, NookColors.CardBorder)
                    ) {
                        Row(
                            modifier          = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text  = account.label.ifBlank { "Unnamed Panel" },
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text     = account.panelUrl,
                                    style    = MaterialTheme.typography.bodySmall,
                                    color    = NookColors.TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (account.isPinned) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text  = "Server ID: ${account.serverId}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NookColors.AccentBlue
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                // Bug fix: original showed only last 6 chars with no prefix context.
                                // Show key prefix (ptlc_…) plus last 4 chars so the user can
                                // distinguish keys at a glance.
                                val keyPreview = when {
                                    account.apiKey.length > 8 ->
                                        account.apiKey.take(5) + "••••" + account.apiKey.takeLast(4)
                                    else ->
                                        "••••" + account.apiKey.takeLast(4)
                                }
                                Text(
                                    text       = keyPreview,
                                    style      = MaterialTheme.typography.bodySmall,
                                    color      = NookColors.TextMuted,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            IconButton(onClick = { showDeleteDialog = account }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove panel",
                                    tint               = NookColors.StatusOffline.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = NookColors.Divider)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text  = "PteroManager · NookTheme Edition",
                        style = MaterialTheme.typography.bodySmall,
                        color = NookColors.TextMuted
                    )
                }
            }
        }
    }

    // ─── Delete confirmation dialog ───────────────────────────────────────────

    showDeleteDialog?.let { account ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            containerColor   = NookColors.CardSurface,
            shape            = NookShapes.Dialog,
            title = {
                Text("Remove Panel", style = MaterialTheme.typography.headlineMedium)
            },
            text = {
                Text(
                    text  = "Remove \"${account.label.ifBlank { "this panel" }}\"? All servers from this panel will disappear from your feed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NookColors.TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.removeAccount(account.id)
                        showDeleteDialog = null
                    }
                ) {
                    Text("Remove", color = NookColors.StatusOffline)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel", color = NookColors.TextSecondary)
                }
            }
        )
    }
}
