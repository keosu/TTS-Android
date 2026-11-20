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
}
