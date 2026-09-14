package com.ameyapb.androidexp.ui.askgemini

import com.ameyapb.androidexp.data.notification.GeminiNotifier

class FakeGeminiNotifier : GeminiNotifier {
    val notifiedReplies = mutableListOf<String>()

    override fun notify(replyText: String) {
        notifiedReplies.add(replyText)
    }
}
