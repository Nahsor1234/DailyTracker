package com.nahsor1234.dailytracker

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small REST client for Supabase Auth and PostgREST.
 * All methods are blocking: call from Dispatchers.IO or a worker thread.
 */
internal class DailyTrackerCloud(context: Context) {
    private val storage = EncryptedSessionStore(context.applicationContext)
    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val apiKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    data class Account(val id: String, val email: String, val verified: Boolean)
    data class CloudEntry(
        val date: LocalDate,
        val completed: Boolean,
        val description: String,
        val updatedAt: Long,
        val timeZoneId: String
    )

    private data class Session(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val userId: String,
        val email: String,
        val verified: Boolean
    )

    fun currentAccount(): Account? = storage.read()?.let {
        if (it.expiresAt > Instant.now().epochSecond + 60) {
            Account(it.userId, it.email, it.verified)
        } else {
            runCatching { refresh(it.refreshToken) }.getOrNull()?.let { fresh ->
                Account(fresh.userId, fresh.email, fresh.verified)
            }
        }
    }

    fun signUp(email: String, password: String) {
        val response = request(
            "POST", "/auth/v1/signup",
            JSONObject().put("email", email.trim()).put("password", password),
            authenticated = false
        )
        val access = response.optString("access_token")
        if (access.isNotBlank()) saveSession(response)
    }

    fun signIn(email: String, password: String): Account {
        val response = request(
            "POST", "/auth/v1/token?grant_type=password",
            JSONObject().put("email", email.trim()).put("password", password),
            authenticated = false
        )
        saveSession(response)
        return requireNotNull(currentAccount())
    }

    fun requestPasswordReset(email: String) {
        request(
            "POST", "/auth/v1/recover",
            JSONObject().put("email", email.trim()),
            authenticated = false
        )
    }

    fun signOut() {
        val session = storage.read()
        if (session != null) runCatching {
            request("POST", "/auth/v1/logout", JSONObject(), authenticated = true)
        }
        storage.clear()
    }

    fun sync(localEntries: List<CloudEntry>) {
        val session = requireNotNull(storage.read()) { "Sign in before syncing." }
        val remote = request(
            "GET", "/rest/v1/daily_progress?select=local_date,completed,description,updated_at,time_zone_id",
            null, authenticated = true
        ) as JSONArray

        val remoteByDate = buildMap {
            for (i in 0 until remote.length()) {
                val item = remote.getJSONObject(i)
                put(item.getString("local_date"), item)
            }
        }
        val toUpload = JSONArray()
        for (entry in localEntries) {
            val date = entry.date.toString()
            val remoteEntry = remoteByDate[date]
            val remoteTime = remoteEntry?.optString("updated_at")?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L)
            } ?: 0L
            // Local wins only when newer; equal timestamps preserve the server copy.
            if (entry.updatedAt > remoteTime) {
                toUpload.put(JSONObject()
                    .put("user_id", session.userId)
                    .put("local_date", date)
                    .put("completed", entry.completed)
                    .put("description", entry.description)
                    .put("time_zone_id", entry.timeZoneId)
                    .put("updated_at", Instant.ofEpochMilli(entry.updatedAt).toString())
                    .put("revision", (remoteEntry?.optLong("revision") ?: 0L) + 1L))
            }
        }
        if (toUpload.length() > 0) {
            request(
                "POST", "/rest/v1/daily_progress?on_conflict=user_id,local_date",
                toUpload, authenticated = true,
                extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
            )
        }
    }

    private fun refresh(refreshToken: String): Session {
        val response = request(
            "POST", "/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", refreshToken),
            authenticated = false
        )
        saveSession(response)
        return requireNotNull(storage.read())
    }

    private fun saveSession(json: JSONObject) {
        val user = json.optJSONObject("user") ?: return
        val email = user.optString("email")
        val verified = !user.isNull("email_confirmed_at") &&
            user.optString("email_confirmed_at").isNotBlank()
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        if (access.isBlank() || refresh.isBlank()) return
        storage.write(Session(
            access, refresh,
            Instant.now().epochSecond + json.optLong("expires_in", 3600),
            user.getString("id"), email, verified
        ))
    }

    private fun request(
        method: String,
        path: String,
        body: Any?,
        authenticated: Boolean,
        extraHeaders: Map<String, String> = emptyMap()
    ): Any {
        require(baseUrl.startsWith("https://") && apiKey.isNotBlank()) {
            "Supabase URL and publishable key are not configured."
        }
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("apikey", apiKey)
            setRequestProperty("Accept", "application/json")
            if (authenticated) {
                val session = requireNotNull(storage.read()) { "Authentication required." }
                setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            } else {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            extraHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optString("msg")
                    .ifBlank { JSONObject(text).optString("message") } }.getOrDefault("")
                throw IllegalStateException(message.ifBlank { "Supabase request failed ($code)." })
            }
            return when {
                text.isBlank() -> JSONObject()
                text.trimStart().startsWith("[") -> JSONArray(text)
                else -> JSONObject(text)
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** AES-256-GCM session encryption backed by a non-exportable Android Keystore key. */
private class EncryptedSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("cloud_session", Context.MODE_PRIVATE)

    @Synchronized fun write(session: DailyTrackerCloud.Session) {
        val plain = JSONObject()
            .put("access", session.accessToken)
            .put("refresh", session.refreshToken)
            .put("expires", session.expiresAt)
            .put("user", session.userId)
            .put("email", session.email)
            .put("verified", session.verified)
            .toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain)
        prefs.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized fun read(): DailyTrackerCloud.Session? = runCatching {
        val iv = Base64.decode(requireNotNull(prefs.getString("iv", null)), Base64.NO_WRAP)
        val data = Base64.decode(requireNotNull(prefs.getString("data", null)), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(data), Charsets.UTF_8))
        DailyTrackerCloud.Session(
            json.getString("access"), json.getString("refresh"), json.getLong("expires"),
            json.getString("user"), json.getString("email"), json.getBoolean("verified")
        )
    }.getOrNull()

    @Synchronized fun clear() { prefs.edit().clear().apply() }

    private fun key(): SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private companion object { const val KEY_ALIAS = "dailytracker_supabase_session" }
}
