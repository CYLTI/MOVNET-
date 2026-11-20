package com.movnet.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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
            connection.connectTimeout = 12000
            connection.readTimeout = 12000
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
                streamUrl = x.optString("stream_url").takeIf { it.startsWith("https://") }
            )
        }
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

    suspend fun loadProfile(token: String): JSONObject = JSONObject(request("/me", token = token))

    suspend fun loadEntitlements(token: String): JSONObject = JSONObject(request("/entitlements", token = token))

    suspend fun updateProfile(token: String, displayName: String): JSONObject {
        val body = JSONObject().put("display_name", displayName.trim())
        return JSONObject(request("/me", "PUT", body, token))
    }
}
