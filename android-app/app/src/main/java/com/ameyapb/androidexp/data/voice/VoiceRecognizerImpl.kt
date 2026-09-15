package com.ameyapb.androidexp.data.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

private const val VOICE_RECOGNITION_UNAVAILABLE_MESSAGE =
    "Voice input error: speech recognition is not available on this device. Install or " +
        "enable a speech recognition service (e.g. the Google app) and try again."

class VoiceRecognizerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceRecognizer {

    override suspend fun listen(): Result<String> {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return Result.failure(Exception(VOICE_RECOGNITION_UNAVAILABLE_MESSAGE))
        }

        return try {
            suspendCancellableCoroutine { continuation ->
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        val transcript = results
                            .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            .orEmpty()
                        recognizer.destroy()
                        if (continuation.isActive) continuation.resume(Result.success(transcript))
                    }

                    override fun onError(error: Int) {
                        recognizer.destroy()
                        if (continuation.isActive) {
                            continuation.resume(Result.failure(Exception(describeVoiceRecognitionError(error))))
                        }
                    }

                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })

                continuation.invokeOnCancellation {
                    recognizer.stopListening()
                    recognizer.destroy()
                }

                recognizer.startListening(buildRecognizerIntent())
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Result.failure(exception)
        }
    }
}

private fun buildRecognizerIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

internal fun describeVoiceRecognitionError(errorCode: Int): String {
    val reason = when (errorCode) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "didn't catch any speech"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission was denied"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "a network error occurred"
        SpeechRecognizer.ERROR_AUDIO -> "an audio recording error occurred"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "the recognizer is busy, try again"
        else -> "an unknown error occurred (code $errorCode)"
    }
    return "Voice input error: $reason."
}
