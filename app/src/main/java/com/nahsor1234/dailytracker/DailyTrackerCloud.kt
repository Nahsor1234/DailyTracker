package com.nahsor1234.dailytracker

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val AUTH_REDIRECT_URI = "com.nahsor1234.dailytracker://auth/callback"

/**
 * Small REST client for Supabase Auth and PostgREST.
 * All methods are blocking and must be called from a worker thread.
 */
internal class DailyTrackerCloud(context: Context) {
    private val storage = EncryptedSessionStore(context.applicationContext)
    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val apiKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    data class Account(val id: String, val email: String, val verified: Boolean)
    data class AuthCallback(val type: String, val account: Account)

    data class CloudEntry(
        val date: LocalDate,
        val completed: Boolean,
        val description: String,
        val updatedAt: Long,
        val timeZoneId: String
    )

    internal data class Session(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val userId: String,
        val email: String,
        val verified: Boolean
    )

    fun currentAccount(): Account? {
        val saved = storage.read() ?: return null
        return if (saved.expiresAt > Instant.now().epochSecond + 60) {
            Account(saved.userId, saved.email, saved.verified)
        } else {
            runCatching { refresh(saved.refreshToken) }
                .getOrNull()
                ?.let { Account(it.userId, it.email, it.verified) }
        }
    }

    fun signUp(email: String, password: String) {
        val response = request(
            "POST",
            "/auth/v1/signup?redirect_to=" + Uri.encode(AUTH_REDIRECT_URI),
            JSONObject().put("email", email.trim()).put("password", password),
            authenticated = false
        ) as JSONObject
        // Supabase may return a user without a session while email confirmation is pending.
        if (response.optString("access_token").isNotBlank()) saveSession(response)
    }

    fun signIn(email: String, password: String): Account {
        val response = request(
            "POST",
            "/auth/v1/token?grant_type=password",
            JSONObject().put("email", email.trim()).put("password", password),
            authenticated = false
        ) as JSONObject
        saveSession(response)
        return requireNotNull(currentAccount()) {
            "Sign-in succeeded but the session could not be stored. Please sign in again."
        }
    }

    fun resendVerification(email: String) {
        request(
            "POST",
            "/auth/v1/resend",
            JSONObject().put("type", "signup").put("email", email.trim()),
            authenticated = false
        )
    }

    fun requestPasswordReset(email: String) {
        request(
            "POST",
            "/auth/v1/recover?redirect_to=" + Uri.encode(AUTH_REDIRECT_URI),
            JSONObject().put("email", email.trim()),
            authenticated = false
        )
    }

    fun signOut() {
        if (storage.read() != null) {
            runCatching {
                request("POST", "/auth/v1/logout", JSONObject(), authenticated = true)
            }
        }
        storage.clear()
    }

    fun acceptAuthCallback(uri: Uri): AuthCallback {
        val parameters = mutableMapOf<String, String>()
        listOfNotNull(uri.encodedQuery, uri.encodedFragment).forEach { encoded ->
            encoded.split("&").forEach { part ->
                if (part.isNotBlank()) {
                    val separator = part.indexOf('=')
                    val key = if (separator >= 0) part.substring(0, separator) else part
                    val value = if (separator >= 0) part.substring(separator + 1) else ""
                    parameters[Uri.decode(key)] = Uri.decode(value)
                }
            }
        }

        val callbackError = parameters["error_description"] ?: parameters["error"]
        require(callbackError.isNullOrBlank()) { callbackError ?: "Authentication callback failed." }
        val accessToken = requireNotNull(parameters["access_token"]) {
            "The authentication link did not include an access token."
        }
        val refreshToken = requireNotNull(parameters["refresh_token"]) {
            "The authentication link did not include a refresh token."
        }
        val type = parameters["type"].orEmpty()
        require(type == "signup" || type == "recovery" || type == "email_change") {
            "Unsupported authentication link type."
        }

        val jwtPayload = accessToken.split('.').getOrNull(1)
            ?: error("The authentication token is invalid.")
        val claims = JSONObject(
            String(
                Base64.decode(
                    jwtPayload,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                ),
                Charsets.UTF_8
            )
        )
        val expiry = parameters["expires_at"]?.toLongOrNull()
            ?: (Instant.now().epochSecond + (parameters["expires_in"]?.toLongOrNull() ?: 3600L))
        storage.write(
            Session(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAt = expiry,
                userId = claims.getString("sub"),
                email = claims.optString("email"),
                verified = false
            )
        )

        val user = request("GET", "/auth/v1/user", null, authenticated = true) as JSONObject
        val verifiedAt = user.optString("email_confirmed_at")
        val session = Session(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = expiry,
            userId = user.getString("id"),
            email = user.optString("email"),
            verified = verifiedAt.isNotBlank() && verifiedAt != "null"
        )
        storage.write(session)
        return AuthCallback(type, Account(session.userId, session.email, session.verified))
    }

    fun updatePassword(newPassword: String): Account {
        require(newPassword.length >= 6) { "Password must contain at least 6 characters." }
        request(
            "PUT",
            "/auth/v1/user",
            JSONObject().put("password", newPassword),
            authenticated = true
        )
        return requireNotNull(currentAccount()) { "Password updated, but the session could not be restored." }
    }

    /**
     * Merge records by local_date. The newest updated_at wins; an exact timestamp tie
     * preserves the remote record. RLS scopes all reads and writes to the active user.
     */
    fun sync(localEntries: List<CloudEntry>): List<CloudEntry> {
        val account = currentAccount() ?: error("Your session has expired. Sign in again.")
        require(account.verified) { "Verify your email before syncing DailyTracker data." }
        val session = requireNotNull(storage.read()) { "Authentication required." }
        require(session.userId == account.id) { "The signed-in account changed. Sign in again." }

        val remoteRows = fetchAllRemoteEntries()
        val remoteByDate = remoteRows.associateBy { it.getString("local_date") }
        val merged = remoteRows.associate { row ->
            val entry = row.toCloudEntry()
            entry.date to entry
        }.toMutableMap()
        val uploads = JSONArray()

        for (local in localEntries) {
            val key = local.date.toString()
            val remote = remoteByDate[key]
            val remoteEntry = remote?.toCloudEntry()
            if (remoteEntry == null || local.updatedAt > remoteEntry.updatedAt) {
                uploads.put(
                    JSONObject()
                        .put("user_id", session.userId)
                        .put("local_date", key)
                        .put("completed", local.completed)
                        .put("description", local.description)
                        .put("time_zone_id", local.timeZoneId)
                        .put("updated_at", Instant.ofEpochMilli(local.updatedAt).toString())
                        .put("revision", (remote?.optLong("revision") ?: 0L) + 1L)
                )
                merged[local.date] = local
            }
            // If remote is newer or timestamps tie, merged already contains the remote row.
        }

        if (uploads.length() > 0) {
            request(
                "POST",
                "/rest/v1/daily_progress?on_conflict=user_id,local_date",
                uploads,
                authenticated = true,
                extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
            )
        }

        return merged.values.sortedBy { it.date }
    }

    private fun fetchAllRemoteEntries(): List<JSONObject> {
        val entries = mutableListOf<JSONObject>()
        var lastDate: String? = null
        while (true) {
            val filter = lastDate?.let { "&local_date=gt.$it" }.orEmpty()
            val path = "/rest/v1/daily_progress" +
                "?select=local_date,completed,description,updated_at,time_zone_id,revision" +
                "&order=local_date.asc&limit=1000$filter"
            val page = request("GET", path, null, authenticated = true) as JSONArray
            for (index in 0 until page.length()) {
                entries += page.getJSONObject(index)
            }
            if (page.length() < 1000) break
            lastDate = page.getJSONObject(page.length() - 1).getString("local_date")
        }
        return entries
    }

    private fun JSONObject.toCloudEntry(): CloudEntry {
        val timestamp = Instant.parse(getString("updated_at")).toEpochMilli()
        return CloudEntry(
            date = LocalDate.parse(getString("local_date")),
            completed = getBoolean("completed"),
            description = optString("description"),
            updatedAt = timestamp,
            timeZoneId = optString("time_zone_id").ifBlank { "UTC" }
        )
    }

    private fun refresh(refreshToken: String): Session {
        val response = request(
            "POST",
            "/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", refreshToken),
            authenticated = false
        ) as JSONObject
        saveSession(response)
        return requireNotNull(storage.read()) { "The refreshed session could not be stored." }
    }

    private fun saveSession(json: JSONObject) {
        val user = json.optJSONObject("user")
            ?: error("Supabase did not return an account for this session.")
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        require(access.isNotBlank() && refresh.isNotBlank()) {
            "Supabase did not issue a persistent session. Verify your email, then sign in."
        }
        val verifiedAt = user.optString("email_confirmed_at")
        storage.write(
            Session(
                accessToken = access,
                refreshToken = refresh,
                expiresAt = Instant.now().epochSecond + json.optLong("expires_in", 3600),
                userId = user.getString("id"),
                email = user.optString("email"),
                verified = verifiedAt.isNotBlank() && verifiedAt != "null"
            )
        )
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
            if (body != null) {
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching {
                    val error = JSONObject(responseText)
                    error.optString("msg")
                        .ifBlank { error.optString("message") }
                        .ifBlank { error.optString("error_description") }
                }.getOrDefault("")
                throw IllegalStateException(message.ifBlank { "Supabase request failed ($code)." })
            }
            return when {
                responseText.isBlank() -> JSONObject()
                responseText.trimStart().startsWith("[") -> JSONArray(responseText)
                else -> JSONObject(responseText)
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** AES-GCM session encryption backed by a non-exportable Android Keystore key. */
private class EncryptedSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("cloud_session", Context.MODE_PRIVATE)

    @Synchronized
    fun write(session: DailyTrackerCloud.Session) {
        val plain = JSONObject()
            .put("access", session.accessToken)
            .put("refresh", session.refreshToken)
            .put("expires", session.expiresAt)
            .put("user", session.userId)
            .put("email", session.email)
            .put("verified", session.verified)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain)
        check(
            prefs.edit()
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit()
        ) { "The encrypted session could not be saved." }
    }

    @Synchronized
    fun read(): DailyTrackerCloud.Session? = runCatching {
        val iv = Base64.decode(requireNotNull(prefs.getString("iv", null)), Base64.NO_WRAP)
        val data = Base64.decode(requireNotNull(prefs.getString("data", null)), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(data), Charsets.UTF_8))
        DailyTrackerCloud.Session(
            json.getString("access"),
            json.getString("refresh"),
            json.getLong("expires"),
            json.getString("user"),
            json.getString("email"),
            json.getBoolean("verified")
        )
    }.getOrNull()

    @Synchronized
    fun clear() {
        prefs.edit().clear().commit()
    }

    private fun key(): SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "dailytracker_supabase_session"
    }
}
