package com.ameyapb.androidexp.data.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

private const val LOG_TAG = "GeminiSpeaker"
private const val TTS_INIT_FAILURE_WARNING =
    "Text-to-speech engine is unavailable; spoken replies will be skipped."
private const val TTS_UTTERANCE_ID = "gemini_reply"

class GeminiSpeakerImpl @Inject constructor(
    @ApplicationContext context: Context,
) : GeminiSpeaker {

    private var isReady = false

    private val textToSpeech: TextToSpeech = TextToSpeech(context) { status ->
        isReady = status == TextToSpeech.SUCCESS
        if (!isReady) {
            Log.w(LOG_TAG, TTS_INIT_FAILURE_WARNING)
        }
    }

    override fun speak(text: String) {
        if (!isReady) {
            return
        }
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, TTS_UTTERANCE_ID)
    }
}
