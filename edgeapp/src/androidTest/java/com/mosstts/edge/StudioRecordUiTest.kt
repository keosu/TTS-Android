package com.mosstts.edge

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.mosstts.edge.data.AudioRecord
import com.mosstts.edge.ui.EdgeTheme
import com.mosstts.edge.ui.RecordCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class StudioRecordUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun recordingActionsOfferMp3AndSubtitleDownloads() {
        val record = AudioRecord(UUID.randomUUID().toString(), "你好，这是语音测试。", "zh-CN-XiaoxiaoNeural", "晓晓",
            "zh-CN", 100, 100, System.currentTimeMillis(), 2544, 15264, true)
        var exported = ""
        compose.setContent {
            EdgeTheme(1) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp)) {
                        RecordCard(record, StudioState(result = record), {}, {}, { exported = it }, {}, {}, {}, compact = false)
                    }
                }
            }
        }
        compose.onNodeWithText("保存 MP3").performClick()
        assertEquals("mp3", exported)
        compose.onNodeWithContentDescription("记录操作").performClick()
        compose.onNodeWithText("下载 SRT 字幕").performClick()
        assertEquals("srt", exported)
        compose.onNodeWithContentDescription("记录操作").performClick()
        compose.onNodeWithText("下载 VTT 字幕").performClick()
        assertEquals("vtt", exported)
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "edge-result.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
