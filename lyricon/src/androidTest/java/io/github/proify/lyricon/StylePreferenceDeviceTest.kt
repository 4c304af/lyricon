package io.github.proify.lyricon

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StylePreferenceDeviceTest {
    @Test fun installedCloudMusicDeclaresDiscoverableProvider() {
        val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getApplicationInfo("io.github.cloudmusic.expressive", android.content.pm.PackageManager.GET_META_DATA)
        assertTrue(info.metaData.getBoolean("lyricon_module"))
        assertFalse(info.metaData.getString("lyricon_module_description").isNullOrBlank())
        assertEquals(0, info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM)
        assertEquals(0, info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)
    }

    @Test fun cachedStyleStillReceivesChangesAfterGarbageCollection() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val prefs = inst.targetContext.getSharedPreferences("cloudbridge_listener_test", Context.MODE_PRIVATE)
        val notified = CountDownLatch(1)
        val callback = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> notified.countDown() }
        val type = Class.forName("io.github.proify.lyricon.xposed.systemui.lyric.LyricPrefs\$StateSharedPreferencesWrapper")
        val constructor = type.getDeclaredConstructor(SharedPreferences::class.java, SharedPreferences.OnSharedPreferenceChangeListener::class.java)
        constructor.isAccessible = true
        val wrapper = constructor.newInstance(prefs, callback)
        val hasChanged = type.getDeclaredMethod("hasChanged").apply { isAccessible = true }
        val reload = type.getDeclaredMethod("reload").apply { isAccessible = true }
        repeat(5) { System.gc(); System.runFinalization(); Thread.sleep(30) }
        inst.runOnMainSync { prefs.edit().putInt("size", prefs.getInt("size", 0) + 1).commit() }
        assertTrue("Cached style must receive live changes after GC", notified.await(3, TimeUnit.SECONDS))
        assertEquals(true, hasChanged.invoke(wrapper))
        assertEquals(true, reload.invoke(wrapper))
        assertEquals(false, hasChanged.invoke(wrapper))
    }
}
