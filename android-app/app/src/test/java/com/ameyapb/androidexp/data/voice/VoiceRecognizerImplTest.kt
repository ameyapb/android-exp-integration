package com.ameyapb.androidexp.data.voice

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.ameyapb.androidexp.ROBOLECTRIC_SDK_LEVEL
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK_LEVEL], application = Application::class)
class VoiceRecognizerImplTest {

    private lateinit var recognizer: VoiceRecognizerImpl

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        registerFakeRecognitionService(context)
        recognizer = VoiceRecognizerImpl(context)
    }

    private fun registerFakeRecognitionService(context: Context) {
        val resolveInfo = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = "com.fake.speech"
                name = "FakeRecognitionService"
            }
        }
        shadowOf(context.packageManager)
            .addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), resolveInfo)
    }

    @Test
    fun `listen returns the transcript when recognition succeeds`() = runTest {
        val resultDeferred = async { recognizer.listen() }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        val bundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("why is the sky blue"))
        }
        shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnResults(bundle)
        advanceUntilIdle()

        assertEquals(Result.success("why is the sky blue"), resultDeferred.getCompleted())
    }

    @Test
    fun `listen returns a failure with the mapped message when recognition errors`() = runTest {
        val resultDeferred = async { recognizer.listen() }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()

        shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            .triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        advanceUntilIdle()

        val result = resultDeferred.getCompleted()
        assertTrue(result.isFailure)
        assertEquals(
            "Voice input error: didn't catch any speech.",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `describeVoiceRecognitionError maps unknown codes to a generic message`() {
        assertEquals(
            "Voice input error: an unknown error occurred (code 999).",
            describeVoiceRecognitionError(999),
        )
    }
}
