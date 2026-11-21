package com.acam.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.acamDataStore by preferencesDataStore(name = "acam_settings")

/** Everything that persists. Auto-saved; there is intentionally no Save button. */
data class PersistedState(
    val customPresets: List<Preset> = emptyList(),
    val activePresetId: String = "normal",
    val params: EffectParams = EffectParams(),
    val dateStamp: DateStampConfig = DateStampConfig(),
    val resolution: ResolutionSpec = ResolutionSpec(),
    val video: VideoSettings = VideoSettings(),
    val camera: CameraPrefs = CameraPrefs(),
    val ui: UiPrefs = UiPrefs()
)

/**
 * Settings and presets are data classes serialized to JSON and stored in a
 * Preferences DataStore. Unknown keys are ignored so future versions can
 * evolve the schema; a corrupt blob falls back to defaults instead of crashing.
 */
class SettingsRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private object Keys {
        val CUSTOM_PRESETS = stringPreferencesKey("custom_presets_json")
        val ACTIVE_ID = stringPreferencesKey("active_preset_id")
        val PARAMS = stringPreferencesKey("params_json")
        val DATE_STAMP = stringPreferencesKey("datestamp_json")
        val RESOLUTION = stringPreferencesKey("resolution_json")
        val VIDEO = stringPreferencesKey("video_json")
        val CAMERA = stringPreferencesKey("camera_json")
        val UI = stringPreferencesKey("ui_json")
    }

    private inline fun <reified T> decode(raw: String?, fallback: T): T {
        if (raw.isNullOrBlank()) return fallback
        return runCatching { json.decodeFromString<T>(raw) }.getOrDefault(fallback)
    }

    val state: Flow<PersistedState> = context.acamDataStore.data.map { p ->
        PersistedState(
            customPresets = decode(p[Keys.CUSTOM_PRESETS], emptyList<Preset>()),
            activePresetId = p[Keys.ACTIVE_ID] ?: "normal",
            params = decode(p[Keys.PARAMS], EffectParams()),
            dateStamp = decode(p[Keys.DATE_STAMP], DateStampConfig()),
            resolution = decode(p[Keys.RESOLUTION], ResolutionSpec()),
            video = decode(p[Keys.VIDEO], VideoSettings()),
            camera = decode(p[Keys.CAMERA], CameraPrefs()),
            ui = decode(p[Keys.UI], UiPrefs())
        )
    }

    suspend fun save(state: PersistedState) {
        context.acamDataStore.edit { p ->
            p[Keys.CUSTOM_PRESETS] = json.encodeToString(state.customPresets)
            p[Keys.ACTIVE_ID] = state.activePresetId
            p[Keys.PARAMS] = json.encodeToString(state.params)
            p[Keys.DATE_STAMP] = json.encodeToString(state.dateStamp)
            p[Keys.RESOLUTION] = json.encodeToString(state.resolution)
            p[Keys.VIDEO] = json.encodeToString(state.video)
            p[Keys.CAMERA] = json.encodeToString(state.camera)
            p[Keys.UI] = json.encodeToString(state.ui)
        }
    }

    /** "Reset ALL": wipes everything back to first-run defaults. */
    suspend fun resetAll() {
        context.acamDataStore.edit { it.clear() }
    }

    // ---- Preset JSON export / import ----

    fun exportPreset(preset: Preset): String = json.encodeToString(preset)

    fun importPreset(text: String): Preset? = runCatching {
        json.decodeFromString<Preset>(text).let {
            // Imported presets become custom; never allow shadowing built-ins.
            it.copy(id = "imported_${System.currentTimeMillis()}", builtin = false)
        }
    }.getOrNull()
}
