package com.ameyapb.androidexp.data.voice

import android.app.Application
import android.speech.tts.TextToSpeech
import com.ameyapb.androidexp.ROBOLECTRIC_SDK_LEVEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK_LEVEL], application = Application::class)
class GeminiSpeakerImplTest {

    private lateinit var speaker: GeminiSpeakerImpl
    private lateinit var shadowTextToSpeech: ShadowTextToSpeech

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        speaker = GeminiSpeakerImpl(context)
        shadowTextToSpeech = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
    }

    @Test
    fun `speak invokes the underlying engine with the given text once init succeeds`() {
        shadowTextToSpeech.onInitListener.onInit(TextToSpeech.SUCCESS)

        speaker.speak("because of Rayleigh scattering")

        assertEquals("because of Rayleigh scattering", shadowTextToSpeech.lastSpokenText)
    }

    @Test
    fun `speak does nothing when init failed`() {
        shadowTextToSpeech.onInitListener.onInit(TextToSpeech.ERROR)

        speaker.speak("because of Rayleigh scattering")

        assertNull(shadowTextToSpeech.lastSpokenText)
    }

    @Test
    fun `speak does nothing before init completes`() {
        speaker.speak("because of Rayleigh scattering")

        assertNull(shadowTextToSpeech.lastSpokenText)
    }
}
