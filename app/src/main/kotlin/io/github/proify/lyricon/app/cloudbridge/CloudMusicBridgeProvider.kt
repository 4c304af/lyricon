/* Cloud Music bridge addition, 2026. Apache-2.0; upstream copyrights retained. */
package io.github.proify.lyricon.app.cloudbridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.service.buildConnectionListener
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.serialization.json.Json

/** Explicit Binder endpoint. Only the installed Cloud Music UID may publish state. */
class CloudMusicBridgeProvider : ContentProvider() {
    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }
    private var provider: LyriconProvider? = null
    @Volatile private var cachedSong: Song? = null
    private var session: String? = null
    private var stamp = 0L
    @Volatile private var revision = 0L
    private var publishedSong: Song? = null
    private var playing = false
    private var position = 0L
    private var sampledAt = 0L
    private var expiry: Runnable? = null
    override fun onCreate() = true

    @Synchronized
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        val uid = Binder.getCallingUid()
        val packages = ctx.packageManager.getPackagesForUid(uid).orEmpty()
        if (!packages.contains(PLAYER)) throw SecurityException("Cloud Bridge caller rejected")
        val incomingSession = extras?.getString("session") ?: throw IllegalArgumentException("Missing session")
        val incomingStamp = extras.getLong("stamp")
        if (incomingStamp < stamp || incomingStamp > SystemClock.elapsedRealtimeNanos() + 1_000_000_000L)
            return Bundle().apply { putBoolean("accepted", false) }
        if (method == "clear") {
            if (incomingSession != session) return Bundle().apply { putBoolean("accepted", false) }
            stamp = incomingStamp
            revision++
            val clearRevision = revision
            cachedSong = null
            main.post {
                if (clearRevision == revision) {
                    expiry?.let(main::removeCallbacks); expiry = null
                    playing = false
                    provider?.player?.setSong(null); provider?.destroy(); provider = null; publishedSong = null
                }
            }
            return Bundle().apply { putBoolean("accepted", true) }
        }
        require(method == "update" && extras?.getInt("version") == 1) { "Unsupported Cloud Bridge version" }
        val payload = extras?.getString("song")
        require(payload == null || payload.toByteArray(Charsets.UTF_8).size <= 200_000) { "Payload too large" }
        val decoded = payload?.let { json.decodeFromString<Song>(it) }
        if (decoded != null) {
            require(decoded.id?.isNotBlank() == true && decoded.duration >= 0 && (decoded.lyrics?.size ?: 0) <= 2000)
            cachedSong = decoded
        }
        if (session != incomingSession && decoded == null) return Bundle().apply { putBoolean("accepted", false) }
        session = incomingSession
        stamp = incomingStamp
        cachedSong ?: return Bundle().apply { putBoolean("accepted", false) }
        revision++
        val updateRevision = revision
        val at = (extras?.getLong("position") ?: 0L).coerceAtLeast(0L)
        val active = extras?.getBoolean("playing") ?: false
        val time = SystemClock.elapsedRealtime()
        main.post {
            if (updateRevision != revision) return@post
            if (provider == null) {
                provider = LyriconFactory.createProvider(ctx.applicationContext, playerPackageName = PLAYER).also { bridge ->
                    bridge.service.addConnectionListener(buildConnectionListener {
                        onConnected { main.post { replay() } }
                        onReconnected { main.post { replay() } }
                    })
                    bridge.register()
                }
            }
            position = at; playing = active; sampledAt = time
            if (publishedSong !== cachedSong) { provider?.player?.setSong(cachedSong); publishedSong = cachedSong }
            provider?.player?.setPlaybackState(active)
            provider?.player?.setPosition(at)
            expiry?.let(main::removeCallbacks)
            expiry = if (active) Runnable {
                if (updateRevision == revision) {
                    // The sender normally publishes position every second. Stop stale lyrics
                    // if its process dies without being able to send clear().
                    position += (SystemClock.elapsedRealtime() - sampledAt).coerceAtLeast(0L)
                    playing = false
                    provider?.player?.setPlaybackState(false)
                    provider?.player?.setPosition(position)
                }
            }.also { main.postDelayed(it, 10_000L) } else null
        }
        return Bundle().apply { putBoolean("accepted", true); putInt("version", 1) }
    }

    private fun replay() {
        val bridge = provider ?: return
        bridge.player.setSong(cachedSong)
        publishedSong = cachedSong
        bridge.player.setPlaybackState(playing)
        bridge.player.setPosition(position + if (playing) (SystemClock.elapsedRealtime() - sampledAt).coerceAtLeast(0L) else 0L)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    companion object { private const val PLAYER = "io.github.cloudmusic.expressive" }
}
