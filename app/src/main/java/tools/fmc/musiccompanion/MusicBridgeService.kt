package tools.fmc.musiccompanion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import tools.fmc.musiccompanion.wire.Cmd
import tools.fmc.musiccompanion.wire.KSession
import tools.fmc.musiccompanion.wire.MUSIC_PACKAGE_NAME
import tools.fmc.musiccompanion.wire.MusicOperation
import tools.fmc.musiccompanion.wire.RpcError
import tools.fmc.musiccompanion.wire.RpcStatus
import tools.fmc.musiccompanion.wire.WireError
import tools.fmc.musiccompanion.wire.bytes
import tools.fmc.musiccompanion.wire.installedPackageName
import tools.fmc.musiccompanion.wire.monotonicMs
import tools.fmc.musiccompanion.wire.musicOperation
import tools.fmc.musiccompanion.wire.parseMusicRequest
import tools.fmc.musiccompanion.wire.replies

/**
 * Holds the standalone BLE link and answers the watch's dedicated M1 RPC one request at a time. Watch replies contain
 * playback state, volume, control status, the displayed title and artist, and optional low-resolution notification
 * artwork. Albums and notification text are never included or logged.
 */
class MusicBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private var link: BleLink? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        BridgeState.set(BridgeStatus.Connecting)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BridgePreferences(this).enabled ||
            !granted(this, Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = serviceNotification("Connecting to the watch")
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (_: Exception) {
            BridgeState.set(BridgeStatus.Error("Android did not allow the foreground connection service."))
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker == null) worker = scope.launch { runBridge() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        link?.disconnect()
        link = null
        BridgeState.set(BridgeStatus.Stopped)
        super.onDestroy()
    }

    private suspend fun runBridge() {
        while (scope.isActive && BridgePreferences(this).enabled) {
            if (!granted(this, Manifest.permission.BLUETOOTH_CONNECT)) {
                BridgeState.set(BridgeStatus.Error("Bluetooth permission was revoked."))
                stopSelf()
                return
            }
            val currentLink = BleLink(this)
            val secureStore = SecureStore(this)
            link = currentLink
            try {
                BridgeState.set(BridgeStatus.Connecting)
                currentLink.connect(scan = true, approvedId = secureStore[APPROVED_WATCH])
                val session = openSession(currentLink)
                secureStore[APPROVED_WATCH] = currentLink.watchId
                BridgeState.set(BridgeStatus.Connected)
                updateNotification("Connected to the watch")
                listen(session, currentLink)
            } catch (timeout: TimeoutCancellationException) {
                BridgeState.set(BridgeStatus.Error("The watch connection timed out."))
                updateNotification("Waiting to reconnect to the watch")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                BridgeState.set(BridgeStatus.Error(error.message ?: "The watch connection failed."))
                updateNotification("Waiting to reconnect to the watch")
            } finally {
                currentLink.disconnect()
                if (link === currentLink) link = null
            }
            if (scope.isActive) delay(RECONNECT_DELAY_MS)
        }
    }

    /** Reuses the Keystore-backed key, or fetches a new one after a watch reset. */
    private suspend fun openSession(bleLink: BleLink): KSession {
        val store = SecureStore(this)
        val account = "watch-${bleLink.watchId.orEmpty()}"
        store[account]?.let { cached ->
            try {
                return KSession.open(bleLink, cached).first
            } catch (timeout: WireError.Timeout) {
                // A timed-out operation can still have a GATT callback in flight. Invalidate this connection so
                // that callback cannot satisfy a later operation; the outer loop reconnects with a fresh GATT.
                bleLink.invalidate()
                throw timeout
            } catch (timeout: TimeoutCancellationException) {
                bleLink.invalidate()
                throw timeout
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // A factory reset changes K; fetch the current pairing secret below.
            }
        }
        val opened = KSession.open(bleLink)
        store[account] = opened.second
        return opened.first
    }

    /** One M1 request is handled and replied to before the next request is read. */
    private suspend fun listen(session: KSession, bleLink: BleLink) {
        val packageNames = mutableMapOf<Int, String>()
        while (scope.isActive) {
            val (slot, message) = coroutineScope {
                val pending = async { session.recvFrom(RECEIVE_TIMEOUT_MS) }
                try {
                    select<Pair<Int, ByteArray>> {
                        pending.onAwait { it }
                        bleLink.disconnected.onAwait { throw BleError.dropped }
                    }
                } finally {
                    pending.cancel()
                }
            }
            if (slot !in 0..7) continue
            val request = parseMusicRequest(message) ?: continue
            val operation = musicOperation(request)
            val responseBudget = when (operation) {
                is MusicOperation.Command -> COMMAND_RESPONSE_BUDGET_MS
                is MusicOperation.Artwork -> ARTWORK_RESPONSE_BUDGET_MS
                is MusicOperation.Track -> TRACK_RESPONSE_BUDGET_MS
                else -> SNAPSHOT_RESPONSE_BUDGET_MS
            }
            val deadline = monotonicMs() + responseBudget
            val packageName = packageNames[slot]
                ?: packageIn(
                    session,
                    slot,
                    (deadline - monotonicMs()).coerceIn(1, PACKAGE_LOOKUP_TIMEOUT_MS),
                )?.also { packageNames[slot] = it }
                ?: continue
            if (packageName != MUSIC_PACKAGE_NAME) continue
            val response = respond(request)
            for (reply in response) {
                val remaining = deadline - monotonicMs()
                if (remaining <= 0L) break
                try {
                    session.send(reply, slot, remaining)
                } catch (timeout: WireError.Timeout) {
                    // A response deadline expiring must not tear down the BLE bridge. The watch
                    // will retry, and the next request can still use this healthy session.
                    break
                }
            }
        }
    }

    /** Read the installed package name for an app slot without changing the watch. */
    private suspend fun packageIn(session: KSession, slot: Int, timeoutMs: Long): String? {
        val reply = session.call(Cmd.INFO, bytes(slot), timeoutMs)
        return installedPackageName(reply)
    }

    private fun respond(request: tools.fmc.musiccompanion.wire.MusicRequest): List<ByteArray> =
        when (val operation = musicOperation(request)) {
            is MusicOperation.Snapshot -> try {
                replies(request.id, RpcStatus.OK, MusicService.snapshot(this))
            } catch (error: RpcError) {
                replies(request.id, error.status)
            } catch (_: SecurityException) {
                replies(request.id, RpcStatus.DENIED)
            } catch (_: Exception) {
                replies(request.id, RpcStatus.UNAVAILABLE)
            }
            is MusicOperation.Command -> try {
                MusicService.command(this, byteArrayOf(operation.command.toByte(), operation.value.toByte()))
                replies(request.id, RpcStatus.OK)
            } catch (error: RpcError) {
                replies(request.id, error.status)
            } catch (_: SecurityException) {
                replies(request.id, RpcStatus.DENIED)
            } catch (_: Exception) {
                replies(request.id, RpcStatus.UNAVAILABLE)
            }
            is MusicOperation.Artwork -> try {
                replies(request.id, RpcStatus.OK, MusicService.artwork(this, operation.nonce, operation.knownGeneration))
            } catch (error: RpcError) {
                replies(request.id, error.status)
            } catch (_: SecurityException) {
                replies(request.id, RpcStatus.DENIED)
            } catch (_: Exception) {
                replies(request.id, RpcStatus.UNAVAILABLE)
            }
            is MusicOperation.Track -> try {
                replies(request.id, RpcStatus.OK, MusicService.track(this))
            } catch (error: RpcError) {
                replies(request.id, error.status)
            } catch (_: SecurityException) {
                replies(request.id, RpcStatus.DENIED)
            } catch (_: Exception) {
                replies(request.id, RpcStatus.UNAVAILABLE)
            }
            null -> replies(request.id, RpcStatus.BAD_REQUEST)
        }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.bridge_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun serviceNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            serviceNotification(text),
        )
    }

    companion object {
        private const val CHANNEL_ID = "watch_bridge"
        private const val NOTIFICATION_ID = 41
        private const val RECONNECT_DELAY_MS = 10_000L
        private const val RECEIVE_TIMEOUT_MS = 60_000L
        private const val PACKAGE_LOOKUP_TIMEOUT_MS = 2_000L
        private const val COMMAND_RESPONSE_BUDGET_MS = 6_500L
        private const val SNAPSHOT_RESPONSE_BUDGET_MS = 8_500L
        private const val TRACK_RESPONSE_BUDGET_MS = 8_000L
        private const val ARTWORK_RESPONSE_BUDGET_MS = 15_000L
        private const val APPROVED_WATCH = "approved-watch"

        fun start(context: Context) {
            if (!granted(context, Manifest.permission.BLUETOOTH_CONNECT)) {
                BridgeState.set(BridgeStatus.Error("Bluetooth permission is required."))
                return
            }
            val intent = Intent(context, MusicBridgeService::class.java)
            try {
                context.applicationContext.startForegroundService(intent)
            } catch (_: Exception) {
                BridgeState.set(BridgeStatus.Error("Android did not allow the foreground connection service."))
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context, MusicBridgeService::class.java))
            BridgeState.set(BridgeStatus.Stopped)
        }
    }
}

fun granted(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
