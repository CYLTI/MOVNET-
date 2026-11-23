package com.movnet.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MovnetApi {
    // Set MOVNET_API_BASE_URL in GitHub Actions/Gradle properties to your deployed HTTPS API URL.
    private val baseUrl: String get() = BuildConfig.MOVNET_API_BASE_URL.trimEnd('/')

    private suspend fun request(path: String, method: String = "GET", body: JSONObject? = null, token: String? = null): String = withContext(Dispatchers.IO) {
        require(baseUrl.startsWith("https://")) { "Сначала настройте MOVNET_API_BASE_URL: нужен HTTPS-адрес опубликованного API." }
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            // Render free instances can sleep between requests. Allow time for a cold start
            // instead of failing the user's request after only 12 seconds.
            connection.connectTimeout = 90000
            connection.readTimeout = 90000
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(response).optString("detail") }.getOrNull()
                val detail = message?.takeIf { it.isNotBlank() }
                val friendly = when {
                    code == 401 && path == "/auth/login" -> "Неверный e-mail или пароль. Проверь раскладку и попробуй ещё раз."
                    code == 409 && path == "/auth/register" -> "Этот e-mail уже зарегистрирован. Переключись на «Войти»."
                    else -> detail ?: "Ошибка сервера ($code)"
                }
                throw IllegalStateException(friendly)
            }
            response
        } catch (e: SocketTimeoutException) {
            throw IllegalStateException("Сервер MOVNET долго просыпается или не отвечает. Проверь интернет и повтори запрос.", e)
        } finally { connection.disconnect() }
    }

     suspend fun authenticate(email: String, password: String, register: Boolean, displayName: String = ""): String {
        val json = JSONObject().put("email", email.trim().lowercase()).put("password", password)
        if (register) json.put("display_name", displayName.trim())
        val result = JSONObject(request(if (register) "/auth/register" else "/auth/login", "POST", json))
        return result.getString("access_token")
    }

    suspend fun loadCatalog(): List<Movie> {
        val array = JSONArray(request("/catalog"))
        return (0 until array.length()).map { i ->
            val x = array.getJSONObject(i)
            Movie(
                title = x.optString("title"), genre = x.optString("genre"),
                year = x.optString("year"), premium = x.optBoolean("premium"),
                emoji = x.optString("emoji", "🎬"), description = x.optString("description"),
                type = x.optString("type"), meta = x.optString("meta"),
                streamUrl = x.optString("stream_url").takeIf { it.startsWith("https://") },
                id = x.optInt("id"),
                posterUrl = x.optString("poster_url").takeIf { it.startsWith("https://") },
                rating = x.optDouble("rating", 0.0).toFloat(),
                ratingCount = x.optInt("rating_count")
            )
        }
    }

    suspend fun loadReviews(movieId: Int): List<MovieReview> {
        val obj = JSONObject(request("/catalog/$movieId/reviews"))
        val array = obj.optJSONArray("reviews") ?: JSONArray()
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            MovieReview(item.optString("author", "Зритель MOVNET"), item.optInt("rating"), item.optString("text"))
        }
    }

    suspend fun submitReview(movieId: Int, rating: Int, text: String, token: String) {
        val body = JSONObject().put("rating", rating).put("text", text)
        request("/catalog/$movieId/reviews", "POST", body, token)
    }

    suspend fun requestPasswordReset(email: String) {
        val body = JSONObject().put("email", email.trim().lowercase())
        request("/auth/password/reset/request", "POST", body)
    }

    suspend fun confirmPasswordReset(email: String, code: String, newPassword: String) {
        val body = JSONObject().put("email", email.trim().lowercase())
            .put("code", code.trim()).put("new_password", newPassword)
        request("/auth/password/reset/confirm", "POST", body)
    }

    suspend fun loadSocialProfile(token: String): JSONObject = JSONObject(request("/social/me", token = token))

    suspend fun sendFriendRequest(publicId: String, token: String): JSONObject {
        val body = JSONObject().put("target_public_id", publicId.trim().uppercase())
        return JSONObject(request("/social/friend-requests", "POST", body, token))
    }

    suspend fun loadFriends(token: String): JSONArray = JSONArray(request("/social/friends", token = token))

    suspend fun loadFriendRequests(token: String): JSONArray = JSONArray(request("/social/friend-requests", token = token))

    suspend fun loadDirectMessages(friendUserId: Int, afterId: Int, token: String): JSONArray =
        JSONArray(request("/social/chats/$friendUserId/messages?after_id=$afterId", token = token))

    suspend fun sendDirectMessage(friendUserId: Int, body: String, token: String): JSONObject {
        val payload = JSONObject().put("body", body)
        return JSONObject(request("/social/chats/$friendUserId/messages", "POST", payload, token))
    }

    suspend fun sendCallSignal(roomId: Int, recipientUserId: Int, kind: String, payload: String, token: String): JSONObject {
        val body = JSONObject().put("recipient_user_id", recipientUserId).put("kind", kind).put("payload", payload)
        return JSONObject(request("/watch/rooms/$roomId/call/signals", "POST", body, token))
    }

    suspend fun loadCallSignals(roomId: Int, afterId: Int, token: String): JSONArray =
        JSONArray(request("/watch/rooms/$roomId/call/signals?after_id=$afterId", token = token))

    suspend fun createDirectCall(recipientUserId: Int, mediaType: String, token: String): JSONObject {
        val body = JSONObject().put("recipient_user_id", recipientUserId).put("media_type", mediaType)
        return JSONObject(request("/social/calls", "POST", body, token))
    }

    suspend fun loadIncomingCalls(token: String): JSONArray =
        JSONArray(request("/social/calls/incoming", token = token))

    suspend fun loadDirectCall(callId: Int, token: String): JSONObject =
        JSONObject(request("/social/calls/$callId", token = token))

    suspend fun updateDirectCallStatus(callId: Int, status: String, token: String): JSONObject =
        JSONObject(request("/social/calls/$callId/status", "PUT", JSONObject().put("status", status), token))

    suspend fun sendDirectCallSignal(callId: Int, kind: String, payload: String, token: String): JSONObject =
        JSONObject(request("/social/calls/$callId/signals", "POST",
            JSONObject().put("kind", kind).put("payload", payload), token))

    suspend fun loadDirectCallSignals(callId: Int, afterId: Int, token: String): JSONArray =
        JSONArray(request("/social/calls/$callId/signals?after_id=$afterId", token = token))

    suspend fun acceptFriendRequest(requestId: Int, token: String) {
        request("/social/friend-requests/$requestId/accept", "POST", JSONObject(), token)
    }

    suspend fun loadProfile(token: String): JSONObject = JSONObject(request("/me", token = token))

    suspend fun loadEntitlements(token: String): JSONObject = JSONObject(request("/entitlements", token = token))

    suspend fun updateProfile(token: String, displayName: String): JSONObject {
        val body = JSONObject().put("display_name", displayName.trim())
        return JSONObject(request("/me", "PUT", body, token))
    }
}
