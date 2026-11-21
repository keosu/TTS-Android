package com.mosstts.edge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.mosstts.edge.data.AudioDocuments
import com.mosstts.edge.data.AudioRecord
import com.mosstts.edge.ui.StudioApp

class MainActivity : ComponentActivity() {
    private val model: StudioViewModel by viewModels()
    private var exportId: String? = null
    private var exportExtension = "mp3"
    private val createDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        val id = exportId
        if (result.resultCode == Activity.RESULT_OK && uri != null && id != null) model.export(id, exportExtension, uri)
        exportId = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportId = savedInstanceState?.getString("export_id")
        exportExtension = savedInstanceState?.getString("export_extension") ?: "mp3"
        enableEdgeToEdge()
        setContent { StudioApp(model, ::export, ::share, ::systemSettings) }
    }
    private fun export(record: AudioRecord, extension: String) {
        exportId = record.id
        exportExtension = extension
        val mime = when (extension) { "mp3" -> "audio/mpeg"; "srt" -> "application/x-subrip"; else -> "text/vtt" }
        runCatching {
            createDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime).putExtra(Intent.EXTRA_TITLE, record.exportName(extension)))
        }.onFailure { model.message("未找到文件保存应用") }
    }
    private fun share(record: AudioRecord) {
        runCatching {
            val uri = AudioDocuments.shareUri(this, model.audioFile(record))
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mpeg")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享音频"))
        }.onFailure { model.message("音频分享失败，请先保存到文件") }
    }
    private fun systemSettings() {
        runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("export_id", exportId)
        outState.putString("export_extension", exportExtension)
        super.onSaveInstanceState(outState)
    }
}
