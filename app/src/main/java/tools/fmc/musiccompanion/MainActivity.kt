package tools.fmc.musiccompanion

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import tools.fmc.musiccompanion.wire.MusicCommand

class MainActivity : ComponentActivity() {
    private val preferences by lazy { BridgePreferences(this) }
    private var permissionVersion by mutableIntStateOf(0)

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionVersion++
        if (preferences.enabled && granted(this, Manifest.permission.BLUETOOTH_CONNECT)) {
            MusicBridgeService.start(this)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        permissionVersion++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionVersion++
        if (preferences.enabled && granted(this, Manifest.permission.BLUETOOTH_CONNECT)) {
            MusicBridgeService.start(this)
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF6750A4))) {
                MusicScreen(
                    permissionVersion = permissionVersion,
                    onRequestBluetooth = ::requestBluetooth,
                    onRequestNotifications = ::requestNotifications,
                    onOpenNotificationAccess = ::openNotificationAccess,
                    onOpenAppSettings = ::openAppSettings,
                    onOpenNotificationSettings = ::openNotificationSettings,
                    onBridgeEnabled = ::setBridgeEnabled,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionVersion++
    }

    private fun requestBluetooth() {
        val missing = buildList {
            if (!granted(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT)) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (!granted(this@MainActivity, Manifest.permission.BLUETOOTH_SCAN)) {
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        if (missing.isEmpty()) {
            if (preferences.enabled) MusicBridgeService.start(this)
        } else {
            bluetoothPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openNotificationAccess() {
        val component = ComponentName(this, MusicNotificationListener::class.java)
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        try {
            startActivity(detail)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null)),
        )
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }

    private fun setBridgeEnabled(enabled: Boolean) {
        preferences.enabled = enabled
        if (enabled) {
            if (granted(this, Manifest.permission.BLUETOOTH_CONNECT)) {
                MusicBridgeService.start(this)
            } else {
                requestBluetooth()
            }
        } else {
            MusicBridgeService.stop(this)
        }
    }
}

private data class PermissionUiState(
    val bluetooth: Boolean,
    val scan: Boolean,
    val notifications: Boolean,
    val listenerAccess: Boolean,
)

@Composable
private fun MusicScreen(
    permissionVersion: Int,
    onRequestBluetooth: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onBridgeEnabled: (Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bridgeStatus by BridgeState.status.collectAsState()
    var bridgeEnabled by remember(permissionVersion) { mutableStateOf(BridgePreferences(context).enabled) }
    var permissions by remember(permissionVersion) {
        mutableStateOf(
            PermissionUiState(
                bluetooth = granted(context, Manifest.permission.BLUETOOTH_CONNECT),
                scan = granted(context, Manifest.permission.BLUETOOTH_SCAN),
                notifications = Build.VERSION.SDK_INT < 33 ||
                    granted(context, Manifest.permission.POST_NOTIFICATIONS),
                listenerAccess = MusicNotificationListener.isAccessGranted(context),
            ),
        )
    }
    var listenerConnected by remember { mutableStateOf(MusicNotificationListener.isConnected()) }
    var description by remember {
        mutableStateOf(
            MusicDescription("", "", "", false, false, 0, 0, null),
        )
    }
    var actionMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (isActive) {
            description = runCatching { MusicService.describe(context) }.getOrElse {
                MusicDescription("", "", "", false, false, 0, 0, null)
            }
            listenerConnected = MusicNotificationListener.isConnected()
            permissions = permissions.copy(
                bluetooth = granted(context, Manifest.permission.BLUETOOTH_CONNECT),
                scan = granted(context, Manifest.permission.BLUETOOTH_SCAN),
                notifications = Build.VERSION.SDK_INT < 33 ||
                    granted(context, Manifest.permission.POST_NOTIFICATIONS),
                listenerAccess = MusicNotificationListener.isAccessGranted(context),
            )
            delay(1000)
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Music Companion",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "A bridge from this phone's media session to Music Controller on the watch.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ConnectionCard(
                status = bridgeStatus,
                enabled = bridgeEnabled,
                onEnabled = { enabled ->
                    bridgeEnabled = enabled
                    onBridgeEnabled(enabled)
                },
                onRequestBluetooth = onRequestBluetooth,
            )
            PermissionCard(
                permissions = permissions,
                listenerConnected = listenerConnected,
                onRequestBluetooth = onRequestBluetooth,
                onRequestNotifications = onRequestNotifications,
                onOpenNotificationAccess = onOpenNotificationAccess,
                onOpenAppSettings = onOpenAppSettings,
                onOpenNotificationSettings = onOpenNotificationSettings,
            )
            CurrentTrackCard(
                description = description,
                actionMessage = actionMessage,
                onCommand = { command, value ->
                    actionMessage = runCatching {
                        MusicService.command(context, byteArrayOf(command.toByte(), value.toByte()))
                        null
                    }.exceptionOrNull()?.let { "The phone could not apply that command." }
                },
            )
            Text(
                text = "The watch receives the current title, artist, a low-resolution copy of the matching " +
                    "notification artwork, playback state, and music volume. Album and notification text stay on " +
                    "this phone. The watch's Bluetooth channel is not private: nearby clients that know the watch " +
                    "pairing key can observe this transferred information and inject messages into the watch-facing " +
                    "channel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    status: BridgeStatus,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    onRequestBluetooth: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Watch bridge", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = when (status) {
                            BridgeStatus.Stopped -> "Stopped"
                            BridgeStatus.Connecting -> "Connecting…"
                            BridgeStatus.Connected -> "Connected"
                            is BridgeStatus.Error -> status.message
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status is BridgeStatus.Error) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabled)
            }
            if (!enabled) {
                OutlinedButton(onClick = onRequestBluetooth) { Text("Grant Bluetooth access") }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    permissions: PermissionUiState,
    listenerConnected: Boolean,
    onRequestBluetooth: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            PermissionRow(
                "Bluetooth",
                if (permissions.bluetooth) "Allowed" else "Required to connect",
                permissions.bluetooth,
                if (permissions.bluetooth) onOpenAppSettings else onRequestBluetooth,
            )
            PermissionRow(
                "Nearby-device scan",
                if (permissions.scan) "Allowed" else "Optional fallback",
                permissions.scan,
                if (permissions.scan) onOpenAppSettings else onRequestBluetooth,
            )
            PermissionRow(
                "Notification access",
                when {
                    !permissions.listenerAccess -> "Required for media-session discovery"
                    listenerConnected -> "Allowed and connected"
                    else -> "Allowed; waiting for the system service"
                },
                permissions.listenerAccess,
                onOpenNotificationAccess,
            )
            PermissionRow(
                "Foreground notifications",
                if (permissions.notifications) "Allowed" else "Recommended",
                permissions.notifications,
                if (permissions.notifications) onOpenNotificationSettings else onRequestNotifications,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    name: String,
    detail: String,
    granted: Boolean,
    action: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        OutlinedButton(onClick = action) { Text(if (granted) "Open" else "Allow") }
    }
}

@Composable
private fun CurrentTrackCard(
    description: MusicDescription,
    actionMessage: String?,
    onCommand: (Int, Int) -> Unit,
) {
    var sliderVolume by remember(description.volume, description.maxVolume) {
        mutableFloatStateOf(description.volume.toFloat())
    }
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Current track", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val artwork = description.artwork
                if (artwork != null) {
                    Image(
                        bitmap = artwork.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(112.dp),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_music),
                        contentDescription = null,
                        modifier = Modifier.size(112.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = description.title.ifEmpty { "Nothing is playing" },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = description.artist.ifEmpty { "No artist" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (description.album.isNotEmpty()) {
                        Text(
                            text = description.album,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = when {
                            !description.active -> "No active media session"
                            description.playing -> "Playing"
                            else -> "Paused"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onCommand(MusicCommand.PREVIOUS, 0) },
                    enabled = description.active,
                    modifier = Modifier.weight(1f),
                ) { Text("Previous") }
                Button(
                    onClick = {
                        onCommand(if (description.playing) MusicCommand.PAUSE else MusicCommand.PLAY, 0)
                    },
                    enabled = description.active,
                    modifier = Modifier.weight(1f),
                ) { Text(if (description.playing) "Pause" else "Play") }
                OutlinedButton(
                    onClick = { onCommand(MusicCommand.NEXT, 0) },
                    enabled = description.active,
                    modifier = Modifier.weight(1f),
                ) { Text("Next") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onCommand(MusicCommand.VOLUME_DOWN, 0) },
                ) { Text("−") }
                Slider(
                    value = sliderVolume.coerceIn(0f, maxOf(1f, description.maxVolume.toFloat())),
                    onValueChange = { sliderVolume = it },
                    onValueChangeFinished = {
                        onCommand(MusicCommand.SET_VOLUME, sliderVolume.toInt())
                    },
                    valueRange = 0f..maxOf(1f, description.maxVolume.toFloat()),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { onCommand(MusicCommand.VOLUME_UP, 0) },
                ) { Text("+") }
            }
            Text(
                text = "Music volume ${description.volume} / ${description.maxVolume}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            actionMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
