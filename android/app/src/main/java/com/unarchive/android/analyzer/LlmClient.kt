package com.unarchive.android.analyzer

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal OpenAI-compatible chat client backed by `HttpURLConnection`.
 *
 * Configured for DeepSeek's `https://api.deepseek.com/chat/completions` endpoint
 * with JSON output. Keeps the project's existing no-OkHttp approach.
 */
class LlmClient(
    private val baseUrl: String = "https://api.deepseek.com",
    private val model: String = "deepseek-v4-flash",
    private val maxTokens: Int = 8_000,
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 120_000,
) {
    suspend fun chat(
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        thinkingEnabled: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val connection = URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")

            val body = JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", userPrompt)),
                )
                .put("response_format", JSONObject().put("type", "json_object"))
                .put("max_tokens", maxTokens)
                .put("thinking", JSONObject().put("type", if (thinkingEnabled) "enabled" else "disabled"))
                .put("stream", false)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream
                    ?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
                throw IOException("LLM 请求失败：HTTP $status $errorBody")
            }
            val response = connection.inputStream.readBytes().toString(Charsets.UTF_8)
            JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } finally {
            connection.disconnect()
        }
    }
}
