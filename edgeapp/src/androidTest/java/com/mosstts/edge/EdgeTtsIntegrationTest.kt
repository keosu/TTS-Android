package com.mosstts.edge

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mosstts.edge.audio.Mp3Decoder
import com.mosstts.edge.service.EdgeTtsService
import com.mosstts.edge.service.SystemTtsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EdgeTtsIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun systemDiscoversIndependentEngineAndSettingsEntry() {
        assertEquals("com.mosstts.edge", context.packageName)
        val services = context.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(context.packageName),
            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_META_DATA,
        )
        assertEquals(1, services.size)
        assertTrue(services.single().serviceInfo.exported)
        services.single().serviceInfo.loadXmlMetaData(context.packageManager, TextToSpeech.Engine.SERVICE_META_DATA)!!.use {
            while (it.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) it.next()
            assertEquals("tts-engine", it.name)
            val settings = it.getAttributeValue("http://schemas.android.com/apk/res/android", "settingsActivity")
            assertNotNull(context.packageManager.resolveActivity(Intent().setClassName(context.packageName, settings), 0))
        }
    }

    @Test fun standardAndroidApiConnectsAndReportsNetworkVoicesWithoutDownloadingModels() {
        val initialized = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val tts = TextToSpeech(context, { status = it; initialized.countDown() }, context.packageName)
        try {
            assertTrue(initialized.await(15, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, status)
            assertTrue(tts.engines.any { it.name == context.packageName })
            val voice = tts.voices.single { it.name == "zh-CN-XiaoxiaoNeural" }
            assertTrue(voice.isNetworkConnectionRequired)
            assertEquals(java.util.Locale.CHINA, voice.locale)
            assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, tts.isLanguageAvailable(java.util.Locale.CHINA))
            assertEquals(TextToSpeech.SUCCESS, tts.setVoice(voice))
        } finally { tts.shutdown() }
    }

    @Test fun voiceDataAndSampleTextChecksWorkWithoutNetwork() {
        ActivityScenario.launchActivityForResult<SystemTtsActivity>(
            Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA).setPackage(context.packageName),
        ).use {
            assertEquals(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, it.result.resultCode)
            assertTrue(it.result.resultData.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES)!!.contains("zho-CHN"))
        }
        ActivityScenario.launchActivityForResult<SystemTtsActivity>(
            Intent(TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT).setPackage(context.packageName)
                .putExtra("language", "zho").putExtra("country", "CHN"),
        ).use {
            assertEquals(TextToSpeech.LANG_AVAILABLE, it.result.resultCode)
            assertFalse(it.result.resultData.getStringExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT).isNullOrBlank())
        }
    }

    @Test fun androidDecoderProduces24kMonoPcm16FromRealEdgeMp3() {
        val file = File.createTempFile("edge-decoder-test", ".mp3", context.cacheDir)
        try {
            instrumentation.context.assets.open("edge-sample.mp3").use { input -> file.outputStream().use { input.copyTo(it) } }
            var received = 0L
            var nonZero = false
            var formatReceived = false
            val total = Mp3Decoder().decode(file, { false }, { rate, channels ->
                assertEquals(24000, rate)
                assertEquals(1, channels)
                formatReceived = true
            }, { pcm ->
                assertTrue(formatReceived)
                assertEquals(0, pcm.size % 2)
                nonZero = nonZero || pcm.any { it != 0.toByte() }
                received += pcm.size
            })
            assertEquals(received, total)
            assertTrue(total > 24000)
            assertTrue(nonZero)
        } finally { file.delete() }
    }

    @Test fun invalidRequestDeliversErrorAndDoneOnSynthesisThread() {
        val events = mutableListOf<String>()
        val thread = Thread.currentThread()
        val callback = object : SynthesisCallback {
            override fun getMaxBufferSize() = 8192
            override fun start(rate: Int, format: Int, channels: Int) = TextToSpeech.SUCCESS
            override fun audioAvailable(bytes: ByteArray, offset: Int, length: Int) = TextToSpeech.SUCCESS
            override fun done(): Int { assertEquals(thread, Thread.currentThread()); events.add("done"); return TextToSpeech.SUCCESS }
            override fun error() = error(TextToSpeech.ERROR_SYNTHESIS)
            override fun error(code: Int) { assertEquals(thread, Thread.currentThread()); events.add("error") }
            override fun hasStarted() = false
            override fun hasFinished() = "done" in events
        }
        EdgeTtsService::class.java.getDeclaredMethod("onSynthesizeText", SynthesisRequest::class.java, SynthesisCallback::class.java)
            .apply { isAccessible = true }.invoke(EdgeTtsService(), SynthesisRequest("", Bundle()), callback)
        assertEquals(listOf("error", "done"), events)
    }

    @Test fun settingsScreenSurvivesActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { activity ->
                fun buttons(view: View): List<Button> = when (view) {
                    is Button -> listOf(view)
                    is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
                    else -> emptyList()
                }
                val labels = buttons(activity.window.decorView).map { it.text.toString() }
                assertTrue(labels.contains("打开系统文字转语音设置"))
                assertTrue(labels.contains("试听"))
                assertTrue(labels.contains("停止朗读"))
            }
        }
    }
}
