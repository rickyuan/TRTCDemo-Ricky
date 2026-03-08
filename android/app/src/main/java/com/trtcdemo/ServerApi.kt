package com.trtcdemo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for the TRTC Demo backend server.
 *
 * All sensitive operations (UserSig generation, Tencent Cloud API calls) are
 * handled server-side. The Android client only sends non-secret parameters.
 */
object ServerApi {

    private const val TAG = "ServerApi"

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetch a UserSig for [userId] from the backend server.
     *
     * @return UserSig string on success, null on failure.
     */
    suspend fun getUserSig(userId: String, expireSeconds: Long = 604800L): String? =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("userId", userId)
                    put("expire", expireSeconds)
                }.toString()
                val resp = post("/api/usersig", body)
                resp?.optString("userSig")?.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                Log.e(TAG, "getUserSig failed: $e")
                null
            }
        }

    /**
     * Ask the backend to start the AI transcription bot in [roomId].
     *
     * @param botUserId  A dedicated userId the SDK bot will use to join the room.
     * @return TaskId string on success, null on failure.
     */
    suspend fun startAITranscription(roomId: Int, botUserId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("roomId", roomId)
                    put("botUserId", botUserId)
                }.toString()
                val resp = post("/api/ai/start", body)
                val errMsg = resp?.optString("error")
                if (!errMsg.isNullOrEmpty()) {
                    Log.e(TAG, "startAITranscription server error: $errMsg")
                    return@withContext null
                }
                resp?.optString("taskId")?.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                Log.e(TAG, "startAITranscription failed: $e")
                null
            }
        }

    /**
     * Ask the backend to stop the running transcription task by its [taskId].
     */
    suspend fun stopAITranscription(taskId: String) =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply { put("taskId", taskId) }.toString()
                post("/api/ai/stop", body)
                Log.d(TAG, "stopAITranscription ok")
            } catch (e: Exception) {
                Log.w(TAG, "stopAITranscription failed: $e")
            }
        }

    // ── HTTP helper ───────────────────────────────────────────────────────────

    private fun post(path: String, jsonBody: String): JSONObject? {
        val url = "${Config.SERVER_URL}$path"
        Log.d(TAG, "POST $url  body=$jsonBody")

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connectTimeout = 10_000
            readTimeout    = 15_000
            doOutput       = true
        }

        conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val responseBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        conn.disconnect()

        Log.d(TAG, "POST $path → HTTP $code  $responseBody")

        return if (responseBody.isNotEmpty()) {
            try { JSONObject(responseBody) } catch (e: Exception) { null }
        } else null
    }
}
