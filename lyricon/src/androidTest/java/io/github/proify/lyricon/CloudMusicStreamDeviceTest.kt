package io.github.proify.lyricon

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Manual integration check: play a song with lyrics in Cloud Music before running. */
@RunWith(AndroidJUnit4::class)
class CloudMusicStreamDeviceTest {
    @Test fun currentMusicStreamReachesSystemUiCentral() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val resolver = inst.targetContext.contentResolver
        val client = requireNotNull(resolver.acquireUnstableContentProviderClient(
            Uri.parse("content://io.github.proify.lyricon.cloudbridge")))
        client.use {
            val endpoint = requireNotNull(client.localContentProvider)
            val songField = endpoint.javaClass.getDeclaredField("cachedSong").apply { isAccessible = true }
            val bridgeField = endpoint.javaClass.getDeclaredField("provider").apply { isAccessible = true }
            val playingField = endpoint.javaClass.getDeclaredField("playing").apply { isAccessible = true }
            var lyricCount = 0
            var status = "DISCONNECTED"
            var playing = false
            val deadline = SystemClock.elapsedRealtime() + 25000
            while (SystemClock.elapsedRealtime() < deadline) {
                inst.runOnMainSync {
                    val song = songField.get(endpoint)
                    lyricCount = (song?.javaClass?.getMethod("getLyrics")?.invoke(song) as? List<*>)?.size ?: 0
                    val bridge = bridgeField.get(endpoint)
                    val service = bridge?.javaClass?.getMethod("getService")?.apply { isAccessible = true }?.invoke(bridge)
                    status = service?.javaClass?.getMethod("getConnectionStatus")?.apply { isAccessible = true }?.invoke(service)?.toString() ?: "DISCONNECTED"
                    playing = playingField.getBoolean(endpoint)
                }
                if (lyricCount > 0 && status == "CONNECTED") break
                SystemClock.sleep(250)
            }
            println("Cloud Bridge live stream: lyricLines=$lyricCount, central=$status, playing=$playing")
            assertTrue("Real music must send structured lyric lines; received $lyricCount", lyricCount > 0)
            assertTrue("Lyricon SDK must connect to SystemUI central, status=$status", status == "CONNECTED")
        }
    }
}
