package com.dtrenn.weather

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.dtrenn.weather.accu.Place

data class SavedPlace(
    val key: String,
    val label: String,
    val link: String?,
)

class ConnectorStore(context: Context) {
    private val prefs: SharedPreferences = create(context.applicationContext)

    fun apiKey(): String? = prefs.getString(API_KEY, null)?.takeIf { it.isNotBlank() }

    fun saveApiKey(key: String) {
        prefs.edit().putString(API_KEY, key.trim()).apply()
    }

    fun saveWidget(widgetId: Int, place: Place, link: String?) {
        write("w$widgetId", place, link)
    }

    fun saveDefault(place: Place, link: String?) {
        write("default", place, link)
    }

    fun widget(widgetId: Int): SavedPlace? = read("w$widgetId")

    fun defaultPlace(): SavedPlace? = read("default")

    fun deleteWidget(widgetId: Int) {
        prefs.edit()
            .remove("w${widgetId}_key")
            .remove("w${widgetId}_label")
            .remove("w${widgetId}_link")
            .apply()
    }

    private fun write(prefix: String, place: Place, link: String?) {
        prefs.edit()
            .putString("${prefix}_key", place.key)
            .putString("${prefix}_label", place.label)
            .putString("${prefix}_link", link)
            .apply()
    }

    private fun read(prefix: String): SavedPlace? {
        val key = prefs.getString("${prefix}_key", null) ?: return null
        val label = prefs.getString("${prefix}_label", null) ?: return null
        if (key.isBlank() || label.isBlank()) return null
        return SavedPlace(key, label, prefs.getString("${prefix}_link", null))
    }

    companion object {
        private const val API_KEY = "api_key"

        private fun create(context: Context): SharedPreferences {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            return EncryptedSharedPreferences.create(
                "accuweather_connector",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
