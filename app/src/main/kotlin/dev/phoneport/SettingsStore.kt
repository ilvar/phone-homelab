package dev.phoneport

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.phoneport.core.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore by preferencesDataStore("settings")
@Serializable data class Settings(
    val baseUrl: String = "http://127.0.0.1:9000", val trustSelfSigned: Boolean = false,
    val endpointId: Int = 0, val endpointName: String = "", val sources: List<CatalogSource> = defaultSources,
)
@Singleton class SettingsStore @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.settingsStore
    private val key = stringPreferencesKey("connection")
    val settings: Flow<Settings> = store.data.map { prefs ->
        prefs[key]?.let { runCatching { catalogJson.decodeFromString<Settings>(it) }.getOrNull() } ?: Settings()
    }
    suspend fun save(settings: Settings) { store.edit { it[key] = catalogJson.encodeToString(settings) } }
}
@Suppress("DEPRECATION")
@Singleton class SecretStore @Inject constructor(@ApplicationContext context: Context) : Credentials {
    private val prefs = EncryptedSharedPreferences.create(context, "credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    private fun get(key: String) = prefs.getString(key, "").orEmpty()
    private fun put(key: String, value: String) { check(prefs.edit().putString(key, value).commit()) { "Could not save credentials" } }
    override var apiKey: String get() = get("apiKey"); set(value) = put("apiKey", value)
    override var jwt: String get() = get("jwt"); set(value) = put("jwt", value)
    override var username: String get() = get("username"); set(value) = put("username", value)
    override var password: String get() = get("password"); set(value) = put("password", value)
    fun replace(other: Credentials) {
        check(prefs.edit().putString("apiKey", other.apiKey).putString("jwt", other.jwt)
            .putString("username", other.username).putString("password", other.password).commit()) { "Could not save credentials" }
    }
}
class MemoryCredentials(
    override var apiKey: String = "", override var jwt: String = "",
    override var username: String = "", override var password: String = "",
) : Credentials
