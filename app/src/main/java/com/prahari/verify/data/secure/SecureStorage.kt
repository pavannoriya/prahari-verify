package com.prahari.verify.data.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage — Prahari JWT token + username + auditor id
 * (AutoFill ke SecureStorage jaisa, lekin Gemini key yahan NAHI hai — is app me
 * photo/Gemini ka koi kaam nahi hai. Password kabhi save nahi hota, sirf JWT.)
 */
class SecureStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "prahari_verify_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // ---- Prahari JWT ----
    fun savePrahariToken(token: String) {
        prefs.edit()
            .putString(KEY_PRAHARI_TOKEN, token)
            .putLong(KEY_PRAHARI_TOKEN_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getPrahariToken(): String? = prefs.getString(KEY_PRAHARI_TOKEN, null)

    fun isPrahariLoggedIn(): Boolean {
        val token = getPrahariToken() ?: return false
        val time = prefs.getLong(KEY_PRAHARI_TOKEN_TIME, 0)
        // 55 min validity (AutoFill jaisa)
        return System.currentTimeMillis() - time < TOKEN_VALIDITY_MS
    }

    fun clearPrahariToken() {
        prefs.edit()
            .remove(KEY_PRAHARI_TOKEN)
            .remove(KEY_PRAHARI_TOKEN_TIME)
            .apply()
    }

    // ---- Prahari username (auto-fill ke liye, password kabhi save nahi hota) ----
    fun savePrahariUsername(username: String) {
        prefs.edit().putString(KEY_PRAHARI_USER, username).apply()
    }

    fun getPrahariUsername(): String? = prefs.getString(KEY_PRAHARI_USER, null)

    // ---- Auditor id (save payload ke liye number chahiye) ----
    // Login response se mile to wahi, warna username AUDIT_<number> se derive hota hai,
    // warna Connect screen me haath se dala hua.
    fun saveAuditorId(id: String) {
        prefs.edit().putString(KEY_AUDITOR_ID, id.trim()).apply()
    }

    fun getAuditorId(): String? = prefs.getString(KEY_AUDITOR_ID, null)?.takeIf { it.isNotBlank() }

    companion object {
        private const val KEY_PRAHARI_TOKEN = "prahari_jwt_token"
        private const val KEY_PRAHARI_TOKEN_TIME = "prahari_token_time"
        private const val KEY_PRAHARI_USER = "prahari_username"
        private const val KEY_AUDITOR_ID = "prahari_auditor_id"
        private const val TOKEN_VALIDITY_MS = 55 * 60 * 1000L
    }
}
