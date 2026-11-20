package org.evsyukov.perigee.reader

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadAloudVoiceTest {
    private fun voice(name: String, locale: Locale, network: Boolean = false, installed: Boolean = true) =
        Voice(name, locale, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, network,
            if (installed) emptySet() else setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))

    @Test
    fun theBookLanguageDoesNotFallBackToANetworkVoiceOrDifferentLanguage() {
        val ru = Locale.forLanguageTag("ru-RU")
        val cloud = voice("cloud", ru, network = true)
        val missing = voice("missing", ru, installed = false)
        val spanish = voice("es", Locale.forLanguageTag("es"))
        assertNull(ReadAloudService.localVoice(setOf(cloud, missing, spanish), cloud, "ru-RU"))
        val offline = voice("offline", ru)
        assertEquals(offline, ReadAloudService.localVoice(setOf(cloud, missing, spanish, offline), cloud, "ru-RU"))
    }

    @Test
    fun anUndeclaredLanguageUsesTheInstalledAndroidDefault() {
        val default = voice("default", Locale.US)
        val other = voice("other", Locale.UK)
        assertEquals(default, ReadAloudService.localVoice(setOf(other, default), default, ""))
    }
}
