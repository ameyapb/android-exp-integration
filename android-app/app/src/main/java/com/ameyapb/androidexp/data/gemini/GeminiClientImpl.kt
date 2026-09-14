package com.ameyapb.androidexp.data.gemini

import com.ameyapb.androidexp.di.GeminiApiKey
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class GeminiClientImpl @Inject constructor(
    @GeminiApiKey private val apiKey: String,
) : GeminiClient {
    override suspend fun generateContent(prompt: String): String = withContext(Dispatchers.IO) {
        val connection = openGenerateContentConnection(apiKey)
        try {
            writeRequestBody(connection, prompt)
            val statusCode = connection.responseCode
            val responseBody = readResponseBody(connection, statusCode)
            parseGenerateContentResponse(statusCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }
}

private fun openGenerateContentConnection(apiKey: String): HttpURLConnection {
    val url = URL("$GEMINI_GENERATE_CONTENT_BASE_URL$GEMINI_MODEL_NAME:generateContent?key=$apiKey")
    return (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = GEMINI_REQUEST_TIMEOUT_MILLIS
        readTimeout = GEMINI_REQUEST_TIMEOUT_MILLIS
        setRequestProperty("Content-Type", "application/json")
    }
}

private fun writeRequestBody(connection: HttpURLConnection, prompt: String) {
    connection.outputStream.use { it.write(buildGenerateContentRequestBody(prompt).toString().toByteArray()) }
}

private fun readResponseBody(connection: HttpURLConnection, statusCode: Int): String {
    val stream = if (statusCode in GEMINI_SUCCESS_STATUS_RANGE) connection.inputStream else connection.errorStream
    return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
}

internal fun buildGenerateContentRequestBody(prompt: String): JSONObject {
    return JSONObject().apply {
        put(
            "contents",
            JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))),
            ),
        )
        put(
            "systemInstruction",
            JSONObject().put(
                "parts",
                JSONArray().put(JSONObject().put("text", GEMINI_SYSTEM_INSTRUCTION)),
            ),
        )
    }
}

internal fun parseGenerateContentResponse(statusCode: Int, responseBody: String): String {
    if (statusCode !in GEMINI_SUCCESS_STATUS_RANGE) {
        throw GeminiHttpException(statusCode, extractErrorMessage(responseBody))
    }
    return extractReplyText(responseBody)
}

internal fun extractReplyText(responseBody: String): String {
    val firstCandidateParts = JSONObject(responseBody)
        .optJSONArray("candidates")
        ?.optJSONObject(0)
        ?.optJSONObject("content")
        ?.optJSONArray("parts")
        ?: return ""
    return firstCandidateParts.optJSONObject(0)?.optString("text").orEmpty().trim()
}

internal fun extractErrorMessage(responseBody: String): String {
    return try {
        JSONObject(responseBody).optJSONObject("error")?.optString("message") ?: responseBody
    } catch (malformedBody: JSONException) {
        responseBody
    }
}
