package com.ameyapb.androidexp.ui.askgemini

import com.ameyapb.androidexp.data.voice.VoiceRecognizer

class FakeVoiceRecognizer : VoiceRecognizer {
    var result: Result<String> = Result.success("fake transcript")

    override suspend fun listen(): Result<String> = result
}
