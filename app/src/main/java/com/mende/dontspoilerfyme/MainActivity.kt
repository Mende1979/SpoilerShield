// MainActivity.kt
package com.mende.dontspoilerfyme

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.mende.dontspoilerfyme.ads.AppOpenAdManager
import com.mende.dontspoilerfyme.billing.BillingManager
import com.mende.dontspoilerfyme.data.SettingsStore
import com.mende.dontspoilerfyme.services.ShieldAutoOffWorker
import com.mende.dontspoilerfyme.services.SpoilerShieldService
import com.mende.dontspoilerfyme.ui.theme.DontSpoilerfyMeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.foundation.interaction.MutableInteractionSource

private const val ACTION_SHIELD_ON = "com.mende.dontspoilerfyme.SHIELD_ON"
private const val ACTION_SHIELD_OFF = "com.mende.dontspoilerfyme.SHIELD_OFF"

private const val APP_OPEN_TEST_UNIT_ID = "ca-app-pub-3940256099942544/9257395921"
private const val APP_OPEN_REAL_UNIT_ID = "ca-app-pub-8432875934272995/4246997922"

private val APP_OPEN_AD_UNIT_ID: String
    get() = if (BuildConfig.DEBUG) APP_OPEN_TEST_UNIT_ID else APP_OPEN_REAL_UNIT_ID
private const val PREMIUM_PRODUCT_ID = "premium_unlock" // ✅ Play Console ID

class MainActivity : ComponentActivity() {

    private lateinit var store: SettingsStore

    private lateinit var billing: BillingManager
    private val billingPrice = MutableStateFlow<String?>(null)
    private val billingError = MutableStateFlow<String?>(null)

    private lateinit var appOpenAdManager: AppOpenAdManager

    // evita show multipli nello stesso start
    private var triedThisStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        store = SettingsStore(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }

        handleShortcutIntent(intent)
        installDynamicShortcuts()

        val requestConfiguration = RequestConfiguration.Builder()
            .setTestDeviceIds(listOf("97EE1B41EDD3186DEFABAFFD501B665F"))
            .build()
        MobileAds.setRequestConfiguration(requestConfiguration)
        MobileAds.initialize(this) {}

        appOpenAdManager = AppOpenAdManager(applicationContext, APP_OPEN_AD_UNIT_ID)
        appOpenAdManager.load()

        billing = BillingManager(
            context = applicationContext,
            productId = PREMIUM_PRODUCT_ID,
            onPremium = { isPremium ->
                lifecycleScope.launch(Dispatchers.IO) {
                    store.setPremiumFromBilling(isPremium)
                }
            },
            onPrice = { price ->
                billingPrice.value = price
            },
            onError = { err ->
                billingError.value = err
            }
        )
        billing.start()

        setContent {
            DontSpoilerfyMeTheme {
                val price by billingPrice.collectAsState()
                val err by billingError.collectAsState()

                AppRoot(
                    store = store,
                    billing = billing,
                    premiumPrice = price,
                    billingError = err
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (triedThisStart) return
        triedThisStart = true

        lifecycleScope.launch {
            // ✅ 0) mai prima che onboarding sia completato
            val onboardingDone = store.onboardingCompleted.first()
            if (!onboardingDone) { triedThisStart = false; return@launch }

            // ✅ 0.5) NON mostrare App Open al primo avvio “vero” post-onboarding
            val launchedOnce = store.getHasLaunchedOnce()
            if (!launchedOnce) {
                store.setHasLaunchedOnce(true)
                // Precarica così al prossimo start è pronta (ma niente show ora)
                appOpenAdManager.load(force = true)
                triedThisStart = false
                return@launch
            }

            // ✅ lascia un attimo a Billing (restore) di settare premium
            delay(450)

            val isPremium = store.isPremium.first()
            if (isPremium) { triedThisStart = false; return@launch }

            val shieldEnabled = store.shieldEnabled.first()
            if (shieldEnabled) { triedThisStart = false; return@launch } // ✅ mai durante shield

            val now = System.currentTimeMillis()
            val last = store.lastAppOpenAdShownEpochMs.first()
            val cooldownMs = 4L * 60L * 60L * 1000L
            if (now - last < cooldownMs) { triedThisStart = false; return@launch } // ✅ max 1 ogni 4h

            appOpenAdManager.load()

            val start = System.currentTimeMillis()
            while (!appOpenAdManager.isAvailable() && System.currentTimeMillis() - start < 1500L) {
                delay(100L)
            }

            if (!appOpenAdManager.isAvailable()) {
                triedThisStart = false
                return@launch
            }

            appOpenAdManager.showIfAvailable(
                activity = this@MainActivity,
                onShown = {
                    lifecycleScope.launch {
                        store.setLastAppOpenAdShownEpochMs(System.currentTimeMillis())
                    }
                },
                onDone = {
                    triedThisStart = false
                }
            )
        }
    }

    override fun onDestroy() {
        try { billing.end() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleShortcutIntent(intent: Intent?) {
        val action = intent?.action ?: return
        val appCtx = applicationContext

        when (action) {
            ACTION_SHIELD_ON -> enableShieldFromShortcut(appCtx)
            ACTION_SHIELD_OFF -> disableShieldFromShortcut(appCtx)
        }

        this.intent = Intent(this.intent).apply { setAction(null) }
    }

    private fun installDynamicShortcuts() {
        val sm = getSystemService(ShortcutManager::class.java) ?: return

        val onIntent = Intent(this, MainActivity::class.java).setAction(ACTION_SHIELD_ON)
        val offIntent = Intent(this, MainActivity::class.java).setAction(ACTION_SHIELD_OFF)

        val s1 = ShortcutInfo.Builder(this, "shield_on_dyn")
            .setShortLabel("Enable Shield")
            .setLongLabel("Enable Spoiler Shield")
            .setIntent(onIntent)
            .setIcon(Icon.createWithResource(this, R.drawable.ic_shortcut_shield_on))
            .build()

        val s2 = ShortcutInfo.Builder(this, "shield_off_dyn")
            .setShortLabel("Disable Shield")
            .setLongLabel("Disable Spoiler Shield")
            .setIntent(offIntent)
            .setIcon(Icon.createWithResource(this, R.drawable.ic_shortcut_shield_off))
            .build()

        sm.dynamicShortcuts = listOf(s1, s2)
    }

    private fun enableShieldFromShortcut(appCtx: android.content.Context) {
        val s = SettingsStore(appCtx)

        lifecycleScope.launch {
            val isPremium = s.isPremium.first()
            val durationMinutes = s.shieldDurationMinutes.first()

            val now = System.currentTimeMillis()
            val durationMs = if (isPremium) {
                durationMinutes.toLong() * 60_000L
            } else {
                2L * 60L * 60L * 1000L
            }

            val endAt = now + durationMs
            s.setShieldEndAtEpochMs(endAt)
            s.setShieldEnabled(true)

            ShieldAutoOffWorker.schedule(appCtx, endAt - now)
            SpoilerShieldService.start(appCtx)
        }
    }

    private fun disableShieldFromShortcut(appCtx: android.content.Context) {
        val s = SettingsStore(appCtx)

        lifecycleScope.launch {
            s.setShieldEnabled(false)
            s.setShieldEndAtEpochMs(0L)

            ShieldAutoOffWorker.cancel(appCtx)
            SpoilerShieldService.stop(appCtx)
        }
    }
}

private enum class Screen { HOME, PREMIUM }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    store: SettingsStore,
    billing: BillingManager,
    premiumPrice: String?,
    billingError: String?
) {
    val scope = rememberCoroutineScope()

    val onboardingDone by store.onboardingCompleted.collectAsState(initial = false)
    var screen by remember { mutableStateOf(Screen.HOME) }

    fun navigateHome() { screen = Screen.HOME }
    fun navigatePremium() { screen = Screen.PREMIUM }

    val snackbarHostState = remember { SnackbarHostState() }

    // mostra errori Billing (se arrivano) come snackbar
    LaunchedEffect(billingError) {
        val msg = billingError?.trim()
        if (!msg.isNullOrEmpty()) snackbarHostState.showSnackbar(msg)
    }

    if (!onboardingDone) {
        OnboardingScreen(
            onContinue = {
                scope.launch { store.setOnboardingCompleted(true) }
            }
        )
        return
    }

    val title = when (screen) {
        Screen.HOME -> "SpoilerShield"
        Screen.PREMIUM -> "Premium"
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    if (screen == Screen.PREMIUM) {
                        IconButton(onClick = ::navigateHome) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        when (screen) {
            Screen.HOME -> HomeScreen(
                modifier = Modifier.padding(innerPadding),
                onOpenPremium = ::navigatePremium,
                snackbarHostState = snackbarHostState,
                store = store
            )

            Screen.PREMIUM -> PremiumScreen(
                modifier = Modifier.padding(innerPadding),
                store = store,
                billing = billing,
                premiumPrice = premiumPrice
            )
        }
    }
}

@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var notifEnabled by remember { mutableStateOf(false) }
    var usageEnabled by remember { mutableStateOf(false) }

    fun refreshPerms() {
        notifEnabled = com.mende.dontspoilerfyme.util.PermissionStatus
            .hasNotificationListenerAccess(ctx)
        usageEnabled = com.mende.dontspoilerfyme.util.PermissionStatus
            .hasUsageAccess(ctx)
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshPerms() }

    val usageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshPerms() }

    DisposableEffect(lifecycleOwner) {
        refreshPerms()
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPerms()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val canContinue = notifEnabled && usageEnabled

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("SpoilerShield", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Delay notifications while you watch live events, so your phone won't spoil the moment.",
            style = MaterialTheme.typography.bodyLarge
        )

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Required permissions", style = MaterialTheme.typography.titleMedium)
                Text(
                    "• Notification access: to delay incoming notifications.\n" +
                            "• Usage access: to auto-enable when you open streaming apps.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Button(
                    onClick = {
                        notifLauncher.launch(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (notifEnabled) "Notification Access: Enabled" else "Enable Notification Access")
                }

                if (!notifEnabled) {
                    Text(
                        "Open the list, find “SpoilerShield”, and turn it ON.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Button(
                    onClick = {
                        usageLauncher.launch(
                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (usageEnabled) "Usage Access: Enabled" else "Enable Usage Access")
                }

                if (!usageEnabled) {
                    Text(
                        "Find “SpoilerShield” and allow Usage Access.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onContinue,
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (canContinue) "Continue" else "Enable permissions to continue")
        }

        Text(
            "Tip: You can always change permissions later in Settings.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenPremium: () -> Unit,
    snackbarHostState: SnackbarHostState,
    store: SettingsStore
) {
    val haptics = LocalHapticFeedback.current
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var claim by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        claim = store.getNextHomeClaim()
    }

    var notifEnabled by remember { mutableStateOf(false) }
    var usageEnabled by remember { mutableStateOf(false) }

    fun refreshPerms() {
        notifEnabled = com.mende.dontspoilerfyme.util.PermissionStatus.hasNotificationListenerAccess(ctx)
        usageEnabled = com.mende.dontspoilerfyme.util.PermissionStatus.hasUsageAccess(ctx)
    }

    DisposableEffect(lifecycleOwner) {
        refreshPerms()
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPerms()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val missing = !(notifEnabled && usageEnabled)

    val shieldEnabled by store.shieldEnabled.collectAsState(initial = false)
    val shieldEndAt by store.shieldEndAtEpochMs.collectAsState(initial = 0L)
    val isPremium by store.isPremium.collectAsState(initial = false)
    val durationMinutes by store.shieldDurationMinutes.collectAsState(initial = 120)
    val delaySeconds by store.delaySeconds.collectAsState(initial = 30)
    val delayed by store.delayedPackages.collectAsState(initial = emptySet())

    fun enableShield() {
        val appCtx = ctx.applicationContext
        scope.launch {
            val now = System.currentTimeMillis()

            val durationMs = if (isPremium) {
                durationMinutes.toLong() * 60_000L
            } else {
                2L * 60L * 60L * 1000L
            }

            val endAt = now + durationMs
            store.setShieldEndAtEpochMs(endAt)
            store.setShieldEnabled(true)

            ShieldAutoOffWorker.schedule(appCtx, endAt - now)
            SpoilerShieldService.start(appCtx)

            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            val msg = if (isPremium) {
                "Shield enabled for $durationMinutes min (delay ${delaySeconds}s)"
            } else {
                "Shield enabled for 2h (free mode)"
            }
            snackbarHostState.showSnackbar(message = msg)
        }
    }

    fun disableShield() {
        val appCtx = ctx.applicationContext
        scope.launch {
            store.setShieldEnabled(false)
            store.setShieldEndAtEpochMs(0L)
            ShieldAutoOffWorker.cancel(appCtx)
            SpoilerShieldService.stop(appCtx)

            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
            snackbarHostState.showSnackbar(message = "Shield disabled")
        }
    }

    val remainingMs = remember(shieldEnabled, shieldEndAt) {
        if (!shieldEnabled || shieldEndAt == 0L) 0L
        else max(0L, shieldEndAt - System.currentTimeMillis())
    }
    val remainingMin = (remainingMs / 60000L).toInt()

    val isDark = isSystemInDarkTheme()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Image(
            painter = painterResource(
                if (isDark) R.drawable.bg_home_halftone_dark else R.drawable.bg_home_halftone_light
            ),
            contentDescription = null,
            modifier = Modifier
                .matchParentSize()
                .alpha(if (isDark) 0.14f else 0.18f),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Protect live events from notification spoilers.", style = MaterialTheme.typography.titleMedium)

            if (missing) {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Setup required", style = MaterialTheme.typography.titleMedium)
                        Text("Enable permissions to delay notifications and auto-trigger on streaming apps.")

                        Button(
                            onClick = {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (notifEnabled) "Notification Access: Enabled" else "Enable Notification Access") }

                        Button(
                            onClick = {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (usageEnabled) "Usage Access: Enabled" else "Enable Usage Access") }
                    }
                }
            }

            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (shieldEnabled) "Shield: ON" else "Shield: OFF",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Switch(
                            checked = shieldEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && missing) {
                                    scope.launch { snackbarHostState.showSnackbar("Enable permissions first") }
                                    return@Switch
                                }
                                if (enabled) enableShield() else disableShield()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.95f),
                                uncheckedTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                                uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.9f)
                            )
                        )
                    }

                    if (isPremium) {
                        val pillBg =
                            if (shieldEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)

                        val pillFg =
                            if (shieldEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant

                        Surface(
                            color = pillBg,
                            contentColor = pillFg,
                            shape = MaterialTheme.shapes.large
                        ) {
                            Text(
                                text = "Delay ${formatDelay(delaySeconds)} • Duration $durationMinutes min • ${delayed.size} apps",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (!isPremium) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Free: 30s delay for 2h.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Go Premium for no ads and full control.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else if (shieldEnabled) {
                        Text(
                            text = "Shield active with your Premium settings.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (shieldEnabled && shieldEndAt != 0L) {
                        Text(
                            "Time remaining: $remainingMin min",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Button(
                        onClick = { enableShield() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !shieldEnabled
                    ) { Text("Enable Spoiler Shield") }

                    OutlinedButton(
                        onClick = onOpenPremium,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (isPremium) "Premium settings" else "Go Premium") }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                shape = MaterialTheme.shapes.large
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lightbulb,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = claim ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Premium screen:
 * - Purchase / Restore
 * - Delay slider
 * - Duration slider
 * - Delay apps selection
 */
private fun sliderToDelaySeconds(pos: Float): Int {
    return when {
        pos <= 40f -> {
            val seconds = (pos / 40f) * 180f
            ((seconds.roundToInt() + 7) / 15) * 15
        }
        pos <= 70f -> {
            val minutes = 3f + ((pos - 40f) / 30f) * 7f
            minutes.roundToInt() * 60
        }
        else -> {
            val minutes = 10f + ((pos - 70f) / 30f) * 50f
            ((minutes / 5f).roundToInt() * 5) * 60
        }
    }.coerceIn(5, 3600)
}

private fun delaySecondsToSlider(seconds: Int): Float {
    return when {
        seconds <= 180 -> (seconds / 180f) * 40f
        seconds <= 600 -> 40f + ((seconds / 60f - 3f) / 7f) * 30f
        else -> 70f + ((seconds / 60f - 10f) / 50f) * 30f
    }.coerceIn(0f, 100f)
}

private fun formatDelay(seconds: Int): String {
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 180 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 60} min"
    }
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var c = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun PremiumScreen(
    modifier: Modifier = Modifier,
    store: SettingsStore,
    billing: BillingManager,
    premiumPrice: String?
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val isPremium by store.isPremium.collectAsState(initial = false)
    val delaySeconds by store.delaySeconds.collectAsState(initial = 30)
    val durationMinutes by store.shieldDurationMinutes.collectAsState(initial = 120)
    val delayed by store.delayedPackages.collectAsState(initial = emptySet())

    var search by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppRow>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val iconCache = remember { mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap?>() }

    val safePackages = remember {
        setOf(
            "com.google.android.dialer",
            "com.android.dialer",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.android.phone",
            "com.google.android.apps.clock",
            "com.android.deskclock",
            "com.android.calendar",
            "com.google.android.calendar",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )
    }
    val ignoredPkgs = remember { safePackages + setOf(ctx.packageName) }

    LaunchedEffect(Unit) {
        isLoading = true
        val launcherRows = loadLauncherApps(ctx.packageManager, ignoredPkgs)
        apps = launcherRows
        isLoading = false

        store.migrateDelayModeIfNeeded(
            launcherPkgs = launcherRows.map { it.packageName }.toSet(),
            ignoredPkgs = ignoredPkgs
        )
    }

    val filtered = remember(apps, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) apps else apps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    val selectedRows = remember(filtered, delayed) {
        filtered.filter { it.packageName in delayed }.sortedBy { it.label.lowercase() }
    }

    val otherRows = remember(filtered, delayed) {
        filtered.filter { it.packageName !in delayed }.sortedBy { it.label.lowercase() }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {

        item {
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Premium features", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Set a custom delay, choose how long the shield stays on, and pick which apps should be delayed.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    val activity = LocalContext.current.findActivity()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (activity != null) billing.launchPurchase(activity)
                            },
                            enabled = !isPremium && activity != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (isPremium) "Premium active"
                                else if (!premiumPrice.isNullOrBlank()) "Unlock Premium • $premiumPrice"
                                else "Unlock Premium"
                            )
                        }

                        OutlinedButton(
                            onClick = { billing.restoreManual() },
                            enabled = !isPremium,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Restore")
                        }
                    }

                    if (!isPremium) {
                        Text(
                            "One-time purchase. Premium removes ads and unlocks full controls.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item {
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Delay notifications", style = MaterialTheme.typography.titleMedium)

                    var sliderPos by remember(delaySeconds) {
                        mutableFloatStateOf(delaySecondsToSlider(delaySeconds))
                    }

                    val previewSeconds = remember(sliderPos) { sliderToDelaySeconds(sliderPos) }

                    Text(text = formatDelay(previewSeconds), style = MaterialTheme.typography.bodyLarge)

                    Text(
                        text = when {
                            previewSeconds < 3 * 60 -> "Great for stream latency protection"
                            previewSeconds < 5 * 60 -> "Perfect for short breaks"
                            else -> "Ideal for longer breaks"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val premiumInactiveTrack = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)

                    Slider(
                        value = sliderPos,
                        onValueChange = { sliderPos = it },
                        onValueChangeFinished = {
                            val qSeconds = sliderToDelaySeconds(sliderPos)
                            sliderPos = delaySecondsToSlider(qSeconds)
                            if (isPremium) scope.launch { store.setDelaySeconds(qSeconds) }
                        },
                        valueRange = 0f..100f,
                        steps = 0,
                        enabled = isPremium,
                        colors = if (isPremium) {
                            SliderDefaults.colors(
                                inactiveTrackColor = premiumInactiveTrack,
                                inactiveTickColor = premiumInactiveTrack
                            )
                        } else {
                            SliderDefaults.colors()
                        }
                    )

                    HorizontalDivider()

                    Text("Shield duration", style = MaterialTheme.typography.titleMedium)
                    Text("$durationMinutes min", style = MaterialTheme.typography.bodyLarge)

                    Slider(
                        value = durationMinutes.toFloat(),
                        onValueChange = { v ->
                            if (isPremium) scope.launch { store.setShieldDurationMinutes(v.toInt()) }
                        },
                        valueRange = 5f..240f,
                        steps = 235,
                        enabled = isPremium,
                        colors = if (isPremium) {
                            SliderDefaults.colors(
                                inactiveTrackColor = premiumInactiveTrack,
                                inactiveTickColor = premiumInactiveTrack,
                                activeTickColor = premiumInactiveTrack
                            )
                        } else {
                            SliderDefaults.colors()
                        }
                    )
                }
            }
        }

        item {
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Delay apps", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Select which apps should be delayed. Selected apps stay on top.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search apps") },
                        singleLine = true
                    )

                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(420.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (selectedRows.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "Selected (${selectedRows.size})",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Spacer(Modifier.height(6.dp))
                                }

                                items(selectedRows, key = { "sel_" + it.packageName }) { app ->
                                    WhitelistRow(
                                        rowKey = "sel_" + app.packageName,
                                        app = app,
                                        checked = true,
                                        enabled = isPremium,
                                        iconCache = iconCache,
                                        onToggle = { newValue ->
                                            scope.launch {
                                                val newSet = delayed.toMutableSet()
                                                if (!newValue) newSet.remove(app.packageName)
                                                store.setDelayedPackages(newSet)
                                            }
                                        }
                                    )
                                }

                                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                            }

                            item {
                                Text(
                                    text = "Other apps",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(Modifier.height(6.dp))
                            }

                            if (otherRows.isEmpty()) {
                                item {
                                    Text("No other apps.", style = MaterialTheme.typography.bodySmall)
                                }
                            } else {
                                items(otherRows, key = { "oth_" + it.packageName }) { app ->
                                    WhitelistRow(
                                        rowKey = "oth_" + app.packageName,
                                        app = app,
                                        checked = delayed.contains(app.packageName),
                                        enabled = isPremium,
                                        iconCache = iconCache,
                                        onToggle = { newValue ->
                                            scope.launch {
                                                val newSet = delayed.toMutableSet()
                                                if (newValue) newSet.add(app.packageName) else newSet.remove(app.packageName)
                                                store.setDelayedPackages(newSet)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WhitelistRow(
    rowKey: String,
    app: AppRow,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    iconCache: MutableMap<String, androidx.compose.ui.graphics.ImageBitmap?>,
) {
    val ctx = LocalContext.current
    val pm = remember { ctx.packageManager }

    var iconBmp by remember(app.packageName) { mutableStateOf(iconCache[app.packageName]) }

    LaunchedEffect(app.packageName) {
        if (iconBmp == null) {
            val disk = loadIconFromDisk(ctx, app.packageName)
            if (disk != null) {
                iconBmp = disk
                iconCache[app.packageName] = disk
                return@LaunchedEffect
            }
        }

        if (iconBmp == null) {
            val loaded: androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.IO) {
                try {
                    val drawable = getBestAppIcon(pm, app.packageName)
                    val bmp = drawable?.toBitmapSafely(96, 96)
                    if (bmp != null) {
                        saveIconToDisk(ctx, app.packageName, bmp)
                        bmp.asImageBitmap()
                    } else null
                } catch (_: Throwable) {
                    null
                }
            }

            iconBmp = loaded
            iconCache[app.packageName] = loaded
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconBmp != null) {
            Image(
                bitmap = iconBmp!!,
                contentDescription = null,
                modifier = Modifier.size(36.dp)
            )
        } else {
            Spacer(modifier = Modifier.size(36.dp))
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        val interactionSource = remember(rowKey, checked) { MutableInteractionSource() }

        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onToggle else null,
            enabled = enabled,
            interactionSource = interactionSource,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.95f),
                uncheckedTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
                checkedBorderColor = MaterialTheme.colorScheme.primary,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.9f)
            )
        )
    }
}

// ---- Helpers ----

private data class AppRow(
    val packageName: String,
    val label: String
)

private suspend fun loadLauncherApps(
    pm: PackageManager,
    ignoredPkgs: Set<String>
): List<AppRow> = withContext(Dispatchers.IO) {

    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(mainIntent, 0)
    }

    resolveInfos
        .map { ri ->
            val pkg = ri.activityInfo.packageName
            val label = try { ri.loadLabel(pm).toString() } catch (_: Throwable) { pkg }
            AppRow(pkg, label)
        }
        .distinctBy { it.packageName }
        .filter { it.packageName !in ignoredPkgs }
        .sortedBy { it.label.lowercase() }
}

private fun getBestAppIcon(pm: PackageManager, pkg: String): android.graphics.drawable.Drawable? {
    return try {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(pkg)
        }

        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        val launcherIcon = resolveInfos.firstOrNull()?.loadIcon(pm)
        launcherIcon ?: run {
            try { pm.getApplicationIcon(pkg) } catch (_: Throwable) { null }
        }
    } catch (_: Throwable) {
        try { pm.getApplicationIcon(pkg) } catch (_: Throwable) { null }
    }
}

private fun android.graphics.drawable.Drawable.toBitmapSafely(w: Int, h: Int): Bitmap? {
    return try {
        val bmp = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        this.setBounds(0, 0, canvas.width, canvas.height)
        this.draw(canvas)
        bmp
    } catch (_: Throwable) {
        null
    }
}

private fun iconFile(context: android.content.Context, pkg: String): java.io.File {
    val dir = java.io.File(context.filesDir, "whitelist_icons")
    if (!dir.exists()) dir.mkdirs()
    val safe = pkg.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    return java.io.File(dir, "$safe.png")
}

private fun loadIconFromDisk(
    context: android.content.Context,
    pkg: String
): androidx.compose.ui.graphics.ImageBitmap? {
    return try {
        val f = iconFile(context, pkg)
        if (!f.exists()) return null
        val bytes = f.readBytes()
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        bmp.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}
private fun saveIconToDisk(
    context: android.content.Context,
    pkg: String,
    bmp: Bitmap
) {
    try {
        val f = iconFile(context, pkg)
        java.io.FileOutputStream(f).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    } catch (_: Throwable) {
        // ignore
    }
}

