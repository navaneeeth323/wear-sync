package com.wearsync.apps

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** What to do with an iPhone app's notifications on the watch. */
enum class AppMode(val label: String) {
    ON("On"),
    SILENT("Silent"),
    OFF("Off");

    fun next(): AppMode = entries[(ordinal + 1) % entries.size]
}

data class KnownApp(
    val appId: String,
    val name: String,
    val lastSeenMillis: Long,
    val mode: AppMode = AppMode.ON,
)

/**
 * Every iPhone app that has sent a notification, with the user's per-app setting.
 * Persisted as one JSON string; shared by BridgeService (BLE thread) and the UI (main thread).
 */
object AppRegistry {
    private const val PREFS = "apps"
    private const val KEY = "known_apps"

    private val _apps = MutableStateFlow<List<KnownApp>>(emptyList())

    /** Most recently seen first. */
    val apps: StateFlow<List<KnownApp>> = _apps.asStateFlow()

    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val json = prefs(context).getString(KEY, null) ?: return
        _apps.value = try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                KnownApp(
                    appId = o.getString("id"),
                    name = o.optString("name"),
                    lastSeenMillis = o.optLong("seen"),
                    mode = runCatching { AppMode.valueOf(o.optString("mode")) }.getOrDefault(AppMode.ON),
                )
            }.sortedByDescending { it.lastSeenMillis }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun mode(context: Context, appId: String): AppMode {
        load(context)
        return _apps.value.firstOrNull { it.appId == appId }?.mode ?: AppMode.ON
    }

    /** Record that [appId] just sent a notification (adds it to the list, or bumps it to the top). */
    @Synchronized
    fun seen(context: Context, appId: String, name: String) {
        if (appId.isBlank()) return
        load(context)
        val existing = _apps.value.firstOrNull { it.appId == appId }
        val updated = (existing ?: KnownApp(appId, name, 0))
            .copy(name = name.ifBlank { existing?.name ?: appId }, lastSeenMillis = System.currentTimeMillis())
        save(context, listOf(updated) + _apps.value.filterNot { it.appId == appId })
    }

    @Synchronized
    fun setMode(context: Context, appId: String, mode: AppMode) {
        load(context)
        save(context, _apps.value.map { if (it.appId == appId) it.copy(mode = mode) else it })
    }

    private fun save(context: Context, apps: List<KnownApp>) {
        _apps.value = apps
        val array = JSONArray()
        apps.forEach {
            array.put(
                JSONObject()
                    .put("id", it.appId)
                    .put("name", it.name)
                    .put("seen", it.lastSeenMillis)
                    .put("mode", it.mode.name),
            )
        }
        prefs(context).edit { putString(KEY, array.toString()) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
