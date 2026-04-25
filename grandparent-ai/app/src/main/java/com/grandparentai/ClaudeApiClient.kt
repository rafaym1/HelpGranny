package com.grandparentai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper over the Anthropic Messages API.
 *
 * Speed-tuned for the interactive agent loop:
 *   - Prompt caching (`cache_control: ephemeral`) on the system prompt — same prompt is sent
 *     every step, so caching saves the encode-and-process tax after the first call.
 *   - Haiku 4.5 for the vision loop (it has vision and is ~2-3× faster than Sonnet) and the
 *     orchestrator (still text-only).
 *   - Caller is expected to trim history before passing it in (we cap at 4 prior turns there).
 */
object ClaudeApiClient {

    private const val TAG = "ClaudeApi"

    private const val API_URL = "https://api.anthropic.com/v1/messages"
    private const val ANTHROPIC_VERSION = "2023-06-01"

    /** Vision-capable, fastest current Claude. Quality vs Sonnet is fine for our small UI flows. */
    private const val MODEL_VISION = "claude-haiku-4-5-20251001"

    /** Routing/classification — same Haiku, no vision needed. */
    private const val MODEL_TEXT = "claude-haiku-4-5-20251001"

    private val JSON = "application/json".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** One turn of the conversation — only the *content* the API needs to reproduce. */
    data class Turn(val role: String, val text: String, val hasImage: Boolean)

    private fun apiKey(): String =
        BuildConfig.ANTHROPIC_API_KEY.takeIf { it.isNotBlank() }
            ?: error("Missing ANTHROPIC_API_KEY (set in local.properties or env)")

    // -------------------------------------------------------------------- public API

    /**
     * Vision call used by the agent loop. [history] is the prior chain of turns (text-only,
     * since each step's image is fresh); each historical text is replayed but historical
     * images are dropped so the prompt stays small.
     */
    suspend fun sendWithImage(
        systemPrompt: String,
        userMessage: String,
        imageBase64: String,
        history: List<Turn> = emptyList(),
        maxTokens: Int = 384,
    ): String = withContext(Dispatchers.IO) {

        val messages = JSONArray()
        for (turn in history) {
            messages.put(
                JSONObject()
                    .put("role", turn.role)
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject().put("type", "text").put("text", turn.text)
                        )
                    )
            )
        }

        val currentContent = JSONArray()
            .put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", "image/png")
                            .put("data", imageBase64)
                    )
            )
            .put(
                JSONObject().put("type", "text").put("text", userMessage)
            )

        messages.put(
            JSONObject().put("role", "user").put("content", currentContent)
        )

        // System prompt as an array block enables prompt caching.
        val systemBlocks = JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", systemPrompt)
                .put("cache_control", JSONObject().put("type", "ephemeral"))
        )

        val body = JSONObject()
            .put("model", MODEL_VISION)
            .put("max_tokens", maxTokens)
            .put("system", systemBlocks)
            .put("messages", messages)

        post(body)
    }

    /** Text-only call — used by [Orchestrator] for routing classification. */
    suspend fun sendTextOnly(
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int = 192,
    ): String = withContext(Dispatchers.IO) {
        val systemBlocks = JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", systemPrompt)
                .put("cache_control", JSONObject().put("type", "ephemeral"))
        )
        val body = JSONObject()
            .put("model", MODEL_TEXT)
            .put("max_tokens", maxTokens)
            .put("system", systemBlocks)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject().put("type", "text").put("text", userMessage)
                            )
                        )
                )
            )
        post(body)
    }

    // -------------------------------------------------------------------- internals

    private fun post(body: JSONObject): String {
        val request = Request.Builder()
            .url(API_URL)
            .addHeader("x-api-key", apiKey())
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code}: ${text.take(400)}")
                error("Claude API error: HTTP ${response.code}")
            }
            return extractText(text)
        }
    }

    /** Pull the assistant's first text block out of a Messages-API response. */
    private fun extractText(json: String): String {
        val root = JSONObject(json)
        val content = root.optJSONArray("content") ?: return ""
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") return block.optString("text", "")
        }
        return ""
    }
}
