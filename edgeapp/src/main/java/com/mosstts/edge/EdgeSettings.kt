package com.mosstts.edge

import android.content.Context
import com.mosstts.edge.protocol.EdgeVoiceCatalog
import java.io.File

class EdgeSettings(context: Context) {
    private val preferences = context.getSharedPreferences("edge_tts", Context.MODE_PRIVATE)
    val catalog = EdgeVoiceCatalog(File(context.filesDir, "edge-voices.json"))
    var voice: String
        get() = preferences.getString("voice", EdgeVoiceCatalog.DEFAULT_VOICE)!!
        set(value) { preferences.edit().putString("voice", value).apply() }
    var rate: Int
        get() = preferences.getInt("rate", 100)
        set(value) { preferences.edit().putInt("rate", value.coerceIn(50, 300)).apply() }
    var pitch: Int
        get() = preferences.getInt("pitch", 100)
        set(value) { preferences.edit().putInt("pitch", value.coerceIn(50, 150)).apply() }
    var studioVoice: String
        get() = preferences.getString("studio_voice", voice)!!
        set(value) { preferences.edit().putString("studio_voice", value).apply() }
    var studioRate: Int
        get() = preferences.getInt("studio_rate", 100)
        set(value) { preferences.edit().putInt("studio_rate", value.coerceIn(50, 300)).apply() }
    var studioPitch: Int
        get() = preferences.getInt("studio_pitch", 100)
        set(value) { preferences.edit().putInt("studio_pitch", value.coerceIn(50, 150)).apply() }
    var draft: String
        get() = preferences.getString("draft", "")!!
        set(value) { preferences.edit().putString("draft", value).apply() }
    var autoPlay: Boolean
        get() = preferences.getBoolean("auto_play", false)
        set(value) { preferences.edit().putBoolean("auto_play", value).apply() }
    /** 0 follows the system, 1 light, 2 dark. */
    var theme: Int
        get() = preferences.getInt("theme", 0)
        set(value) { preferences.edit().putInt("theme", value.coerceIn(0, 2)).apply() }
}
