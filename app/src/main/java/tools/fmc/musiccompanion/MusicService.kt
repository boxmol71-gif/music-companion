package tools.fmc.musiccompanion

import android.annotation.SuppressLint
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.StatusBarNotification
import tools.fmc.musiccompanion.wire.MusicCommand
import tools.fmc.musiccompanion.wire.RpcError
import tools.fmc.musiccompanion.wire.RpcStatus
import tools.fmc.musiccompanion.wire.musicSnapshotRecords

/** The current track as shown by the phone UI. */
data class MusicDescription(
    val title: String,
    val artist: String,
    val album: String,
    val playing: Boolean,
    val active: Boolean,
    val volume: Int,
    val maxVolume: Int,
    val artwork: Bitmap?,
)

/**
 * Reads Android's active media session and the matching media notification for the local phone UI. Artwork is taken
 * only from that notification's large icon or EXTRA_PICTURE and never leaves the phone; MediaMetadata artwork is not used.
 */
object MusicService {
    private const val MEDIA_STYLE = "android.app.Notification.MediaStyle"
    private const val DECORATED_MEDIA_STYLE = "android.app.Notification.DecoratedMediaCustomViewStyle"

    private data class ActiveMedia(
        val controller: MediaController?,
        val metadataNotification: StatusBarNotification?,
        val artworkNotification: StatusBarNotification?,
    )

    /** A local description of the active media session and the music stream volume. */
    fun describe(context: Context): MusicDescription {
        val active = activeMedia(context)
        val audio = context.getSystemService(AudioManager::class.java)
        val controller = active.controller
        return MusicDescription(
            title = title(controller, active.metadataNotification),
            artist = artist(controller, active.metadataNotification),
            album = controller?.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING,
            active = controller != null,
            volume = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0,
            maxVolume = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0,
            artwork = active.artworkNotification?.let { artworkBitmap(context, it) },
        )
    }

    /** The watch's snapshot: non-personal playback flags and music volume only. */
    fun snapshot(context: Context): List<ByteArray> {
        requireListenerAccess(context)
        val controller = activeController(context)
        val audio = context.getSystemService(AudioManager::class.java)
        return musicSnapshotRecords(
            playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING,
            active = controller != null,
            volume = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0,
            maxVolume = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0,
        )
    }

    /** A playback or phone music-volume command. */
    fun command(context: Context, args: ByteArray) {
        if (args.size != 2) throw RpcError(RpcStatus.BAD_REQUEST)
        val command = args[0].toInt() and 0xFF
        if (!MusicCommand.isValid(command)) throw RpcError(RpcStatus.BAD_REQUEST)
        val value = args[1].toInt() and 0xFF
        when (command) {
            MusicCommand.VOLUME_UP, MusicCommand.VOLUME_DOWN -> adjustVolume(context, command)
            MusicCommand.SET_VOLUME -> {
                val audio = audioManager(context)
                val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, value.coerceIn(0, maximum), 0)
            }
            else -> {
                requireListenerAccess(context)
                val controller = activeController(context) ?: throw RpcError(RpcStatus.UNAVAILABLE)
                val state = controller.playbackState ?: throw RpcError(RpcStatus.UNAVAILABLE)
                val controls = controller.transportControls
                when (command) {
                    MusicCommand.PLAY -> {
                        requireAction(state, PlaybackState.ACTION_PLAY)
                        controls.play()
                    }
                    MusicCommand.PAUSE -> {
                        requireAction(state, PlaybackState.ACTION_PAUSE)
                        controls.pause()
                    }
                    MusicCommand.TOGGLE -> if (state.state == PlaybackState.STATE_PLAYING) {
                        requireAction(state, PlaybackState.ACTION_PAUSE)
                        controls.pause()
                    } else {
                        requireAction(state, PlaybackState.ACTION_PLAY)
                        controls.play()
                    }
                    MusicCommand.PREVIOUS -> {
                        requireAction(state, PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                        controls.skipToPrevious()
                    }
                    MusicCommand.NEXT -> {
                        requireAction(state, PlaybackState.ACTION_SKIP_TO_NEXT)
                        controls.skipToNext()
                    }
                }
            }
        }
    }

    private fun adjustVolume(context: Context, command: Int) {
        val audio = audioManager(context)
        val direction = if (command == MusicCommand.VOLUME_UP) {
            AudioManager.ADJUST_RAISE
        } else {
            AudioManager.ADJUST_LOWER
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
    }

    private fun audioManager(context: Context): AudioManager =
        context.getSystemService(AudioManager::class.java) ?: throw RpcError(RpcStatus.UNAVAILABLE)

    private fun requireListenerAccess(context: Context) {
        when (MusicNotificationListener.accessState(context)) {
            ListenerAccess.GRANTED -> Unit
            ListenerAccess.NEVER_GRANTED -> throw RpcError(RpcStatus.ASK)
            ListenerAccess.REVOKED -> throw RpcError(RpcStatus.DENIED)
        }
    }

    private fun requireAction(state: PlaybackState, action: Long) {
        if (state.actions and action == 0L) throw RpcError(RpcStatus.UNAVAILABLE)
    }

    /** Selects the active media controller without reading notification content. */
    private fun activeController(context: Context): MediaController? {
        val controllers = context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(context, MusicNotificationListener::class.java))
        return controllers.maxByOrNull {
            if (it.playbackState?.state == PlaybackState.STATE_PLAYING) 1 else 0
        }
    }

    /** Selects the active controller and the notification that exactly matches its token. */
    private fun activeMedia(context: Context): ActiveMedia {
        val controller = activeController(context) ?: return ActiveMedia(null, null, null)
        val exact = MusicNotificationListener.activeNotifications()
            .filter(::isMediaStyle)
            .firstOrNull { mediaToken(it) == controller.sessionToken }

        // Metadata and artwork fallbacks are accepted only from the notification with the same
        // MediaSession token. A different session from the same app is never used as a fallback.
        return ActiveMedia(controller, exact, exact)
    }

    private fun isMediaStyle(notification: StatusBarNotification): Boolean {
        val template = notification.notification.extras.getString(Notification.EXTRA_TEMPLATE)
        return template == MEDIA_STYLE || template == DECORATED_MEDIA_STYLE ||
            template?.contains("MediaStyle") == true || mediaToken(notification) != null
    }

    private fun mediaToken(notification: StatusBarNotification): MediaSession.Token? = runCatching {
        val extras = notification.notification.extras
        if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
        }
    }.getOrNull()

    private fun title(controller: MediaController?, notification: StatusBarNotification?): String {
        val metadataTitle = controller?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (metadataTitle.isNotEmpty()) return metadataTitle
        return notification?.let { extraText(it, Notification.EXTRA_TITLE) }.orEmpty()
    }

    private fun artist(controller: MediaController?, notification: StatusBarNotification?): String {
        val metadata = controller?.metadata
        val metadataArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        if (metadataArtist.isNotEmpty()) return metadataArtist
        val albumArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        if (albumArtist.isNotEmpty()) return albumArtist
        notification ?: return ""
        val subText = extraText(notification, Notification.EXTRA_SUB_TEXT)
        return subText.ifEmpty { extraText(notification, Notification.EXTRA_TEXT) }
    }

    private fun extraText(notification: StatusBarNotification, key: String): String =
        notification.notification.extras.getCharSequence(key)?.toString().orEmpty()

    @Suppress("DEPRECATION")
    @SuppressLint("UseKtx")
    private fun artworkBitmap(context: Context, notification: StatusBarNotification): Bitmap? {
        val extras = notification.notification.extras
        val picture = if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        } else {
            extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap
        }
        // Modern Android stores setLargeIcon() as an Icon in EXTRA_LARGE_ICON. The public Bitmap
        // field is only populated by older or custom notification builders.
        val modernLargeIcon = notification.notification.getLargeIcon()?.let { icon ->
            runCatching {
                val drawable = icon.loadDrawable(context) ?: return@runCatching null
                if (drawable is BitmapDrawable) {
                    drawable.bitmap
                } else {
                    val width = drawable.intrinsicWidth.coerceAtLeast(1)
                    val height = drawable.intrinsicHeight.coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        drawable.setBounds(0, 0, width, height)
                        drawable.draw(Canvas(bitmap))
                    }
                }
            }.getOrNull()
        }
        return modernLargeIcon ?: picture ?: notification.notification.largeIcon
    }
}
