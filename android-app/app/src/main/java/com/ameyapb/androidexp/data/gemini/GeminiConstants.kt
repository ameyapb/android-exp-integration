package com.ameyapb.androidexp.data.gemini

internal const val GEMINI_MODEL_NAME = "gemini-3.1-flash-lite"

internal const val GEMINI_SYSTEM_INSTRUCTION =
    "Reply in plain spoken prose only, as if answering aloud. Do not use markdown, headers, " +
        "bullet points, numbered lists, asterisks, or any other formatting or special characters. " +
        "Keep the reply short and conversational."

internal const val GEMINI_GENERATE_CONTENT_BASE_URL =
    "https://generativelanguage.googleapis.com/v1beta/models/"

internal const val GEMINI_REQUEST_TIMEOUT_MILLIS = 30_000

internal val GEMINI_SUCCESS_STATUS_RANGE = 200..299
