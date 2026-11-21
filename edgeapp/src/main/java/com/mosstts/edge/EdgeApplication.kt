package com.mosstts.edge

import android.app.Application
import com.mosstts.edge.data.AudioHistoryStore
import java.io.File

class EdgeApplication : Application() {
    val history by lazy { AudioHistoryStore(File(filesDir, "audio_history")) }
}
