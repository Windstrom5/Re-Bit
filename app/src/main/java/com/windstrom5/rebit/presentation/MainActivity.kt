package com.windstrom5.rebit.presentation

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.windstrom5.rebit.presentation.navigation.AppDestination
import com.windstrom5.rebit.presentation.screen.AbxScreen
import com.windstrom5.rebit.presentation.screen.EqTrainerScreen
import com.windstrom5.rebit.presentation.screen.GamesScreen
import com.windstrom5.rebit.presentation.screen.DacScreen
import com.windstrom5.rebit.presentation.screen.DiagnosticsScreen
import com.windstrom5.rebit.presentation.screen.HomeScreen
import com.windstrom5.rebit.presentation.screen.InsightsScreen
import com.windstrom5.rebit.presentation.screen.LibraryScreen
import com.windstrom5.rebit.presentation.screen.NewsScreen
import com.windstrom5.rebit.presentation.screen.TestSignalsScreen
import com.windstrom5.rebit.presentation.screen.WrappedScreen
import com.windstrom5.rebit.presentation.screen.PlayerScreen
import com.windstrom5.rebit.presentation.screen.SettingsScreen
import com.windstrom5.rebit.presentation.screen.StreamingScreen
import com.windstrom5.rebit.presentation.theme.BitPerfectUsbTheme
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.OutlineSubtle
import com.windstrom5.rebit.presentation.theme.SignalBlue
import com.windstrom5.rebit.presentation.theme.SignalTeal
import com.windstrom5.rebit.presentation.theme.SurfacePanel
import com.windstrom5.rebit.presentation.theme.TelemetryFontFamily
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.presentation.viewmodel.KeepScreenOnViewModel
import org.koin.androidx.compose.koinViewModel

class MainActivity : ComponentActivity() {
    /** Route requested by a notification tap (e.g. "news"); consumed once by [BitPerfectApp]. */
    private val pendingRoute = androidx.compose.runtime.mutableStateOf<String?>(null)

    private val streamingManager: com.windstrom5.rebit.streaming.StreamingManager by inject()

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        pendingRoute.value = intent.getStringExtra(com.windstrom5.rebit.data.news.NewsNotifier.EXTRA_ROUTE)
        handleSpotifyRedirect(intent)
    }

    /** Finishes Spotify sign-in when Android hands back bitperfectusb://spotify-callback. */
    private fun handleSpotifyRedirect(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "bitperfectusb" || data.host != "spotify-callback") return
        pendingRoute.value = "streaming"
        lifecycleScope.launch {
            try {
                streamingManager.handleSpotifyRedirect(data)
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    this@MainActivity, e.message ?: "Spotify sign-in failed.", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val hardwareVolume: com.windstrom5.rebit.playback.HardwareVolumeRouter by inject()
    private var volumeToast: android.widget.Toast? = null

    /**
     * With USB Direct and a DAC that has a volume control, the phone's volume buttons set the DAC volume.
     * (With the screen off or the app in the background, Android routes the keys through the media session
     * instead; see HardwareVolumeRouter.) Otherwise the keys are left to Android.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            val level = hardwareVolume.adjust(if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
            if (level != null) {
                volumeToast?.cancel()
                volumeToast = android.widget.Toast.makeText(this, "DAC volume ${hardwareVolume.percent(level)}%", android.widget.Toast.LENGTH_SHORT)
                volumeToast?.show()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        // Swallow the release too while keys drive the DAC, so Android doesn't also react to it.
        if ((keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) &&
            hardwareVolume.active.value
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        // Opening the app is a good moment to look for news right away.
        com.windstrom5.rebit.data.news.NewsScheduler.runIfStale(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingRoute.value = intent?.getStringExtra(com.windstrom5.rebit.data.news.NewsNotifier.EXTRA_ROUTE)
        handleSpotifyRedirect(intent)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        setContent {
            BitPerfectUsbTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    BitPerfectApp(pendingRoute)
                }
            }
        }
    }
}

@Composable
private fun BitPerfectApp(pendingRoute: androidx.compose.runtime.MutableState<String?>) {
    val navController = rememberNavController()
    KeepScreenOnEffect()

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    androidx.compose.runtime.LaunchedEffect(pendingRoute.value) {
        pendingRoute.value?.let { route ->
            navController.navigate(route) { launchSingleTop = true }
            pendingRoute.value = null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // Wrapped is a full-screen story; everything else keeps the navigation bar.
            if (currentRoute != "wrapped") {
                CustomBottomBar(
                    navController = navController,
                    destinations = AppDestination.entries
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.HOME.route,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) {
            composable(AppDestination.HOME.route) {
                HomeScreen(
                    onOpenWrapped = { navController.navigate("wrapped") { launchSingleTop = true } },
                    onOpenNews = { navController.navigate("news") { launchSingleTop = true } },
                    onOpenLab = { navController.navigate("testsignals") { launchSingleTop = true } },
                    onOpenStreaming = { navController.navigate("streaming") { launchSingleTop = true } },
                    onNavigateToPlayer = {
                        navController.navigate(AppDestination.PLAYER.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(AppDestination.LIBRARY.route) {
                LibraryScreen(
                    onOpenInsights = { navController.navigate("insights") { launchSingleTop = true } },
                    onNavigateToPlayer = {
                        navController.navigate(AppDestination.PLAYER.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(AppDestination.PLAYER.route) { PlayerScreen() }
            composable(AppDestination.GAMES.route) {
                GamesScreen(
                    onOpenAbx = { navController.navigate("games/abx") { launchSingleTop = true } },
                    onOpenEqTrainer = { navController.navigate("games/eq") { launchSingleTop = true } }
                )
            }
            composable("games/abx") { AbxScreen(onBack = { navController.popBackStack() }) }
            composable("games/eq") { EqTrainerScreen(onBack = { navController.popBackStack() }) }
            composable(AppDestination.SETTINGS.route) { SettingsScreen() }
            composable("dac") { DacScreen(onBack = { navController.popBackStack() }) }
            composable("insights") { InsightsScreen(onBack = { navController.popBackStack() }) }
            composable("testsignals") { TestSignalsScreen(onBack = { navController.popBackStack() }) }
            composable("news") { NewsScreen(onBack = { navController.popBackStack() }) }
            composable("streaming") { StreamingScreen(onBack = { navController.popBackStack() }) }
            composable("wrapped") { WrappedScreen(onClose = { navController.popBackStack() }) }
            composable("diagnostics") { DiagnosticsScreen(onBack = { navController.popBackStack() }) }
        }
    }
}

@Composable
private fun CustomBottomBar(
    navController: androidx.navigation.NavController,
    destinations: List<AppDestination>
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp
    ) {
        destinations.forEach { destination ->
            // A game opened from the Games hub still counts as being on the Games tab.
            val onRoot = currentRoute == destination.route
            val selected = onRoot || (destination == AppDestination.GAMES && currentRoute?.startsWith("games") == true)
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (selected && !onRoot) {
                        navController.popBackStack(destination.route, inclusive = false)
                    } else if (!selected) {
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = SignalBlue,
                    selectedTextColor = SignalBlue,
                    indicatorColor = SignalBlue.copy(alpha = 0.16f),
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary
                )
            )
        }
    }
}

@Composable
private fun KeepScreenOnEffect(viewModel: KeepScreenOnViewModel = koinViewModel()) {
    val shouldKeepOn by viewModel.shouldKeepScreenOn.collectAsStateWithLifecycle()
    val view = LocalView.current

    LaunchedEffect(shouldKeepOn) {
        view.keepScreenOn = shouldKeepOn
    }
}
