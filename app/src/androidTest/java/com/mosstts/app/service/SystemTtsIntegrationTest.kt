package com.mosstts.app.service

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mosstts.app.MossTTSApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SystemTtsIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun packagedEngineAndSettingsEntryAreDiscoverable() {
        val services = context.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(context.packageName),
            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_META_DATA,
        )
        assertEquals(1, services.size)
        val info = services.single().serviceInfo
        assertTrue(info.exported)
        val metadata = info.loadXmlMetaData(context.packageManager, TextToSpeech.Engine.SERVICE_META_DATA)
        assertNotNull(metadata)
        metadata!!.use {
            while (it.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) it.next()
            assertEquals("tts-engine", it.name)
            val settings = it.getAttributeValue("http://schemas.android.com/apk/res/android", "settingsActivity")
            assertNotNull(context.packageManager.resolveActivity(
                Intent().setClassName(context.packageName, settings), 0,
            ))
        }
        for (action in listOf(
            TextToSpeech.Engine.ACTION_CHECK_TTS_DATA,
            TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA,
            TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT,
        )) {
            assertNotNull(context.packageManager.resolveActivity(
                Intent(action).setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY,
            ))
        }
    }

    @Test
    fun androidTextToSpeechApiListsAndConnectsToTheEngine() {
        val initialized = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val tts = TextToSpeech(context, {
            status = it
            initialized.countDown()
        }, context.packageName)
        try {
            assertTrue("Engine initialization timed out", initialized.await(15, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, status)
            assertTrue(tts.engines.any { it.name == context.packageName })
            val voice = tts.voices.single { it.name == "zh-CN" }
            assertEquals(java.util.Locale.CHINA, voice.locale)
            assertFalse(voice.isNetworkConnectionRequired)
        } finally {
            tts.shutdown()
        }
    }

    @Test
    fun voiceDataCheckReportsActualDownloadedState() {
        val ready = (context.applicationContext as MossTTSApp).modelDownloader.isModelReady()
        val intent = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA).setPackage(context.packageName)
        ActivityScenario.launchActivityForResult<SystemTtsActivity>(intent).use { scenario ->
            val result = scenario.result
            assertEquals(
                if (ready) TextToSpeech.Engine.CHECK_VOICE_DATA_PASS else TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL,
                result.resultCode,
            )
            val available = result.resultData.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES)
            val unavailable = result.resultData.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES)
            assertEquals(if (ready) listOf("zho", "zho-CHN") else emptyList<String>(), available)
            assertEquals(if (ready) emptyList<String>() else listOf("zho", "zho-CHN"), unavailable)
        }
    }

    @Test
    fun systemSettingsCanRequestChineseSampleTextUsingIsoThreeLetterCodes() {
        val intent = Intent(TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT)
            .setPackage(context.packageName)
            .putExtra("language", "zho")
            .putExtra("country", "CHN")
        ActivityScenario.launchActivityForResult<SystemTtsActivity>(intent).use { scenario ->
            val result = scenario.result
            assertEquals(TextToSpeech.LANG_AVAILABLE, result.resultCode)
            assertFalse(result.resultData.getStringExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT).isNullOrBlank())
        }
    }

    @Test
    fun synthesisFailureIsDeliveredBeforeOnSynthesizeTextReturns() {
        val events = mutableListOf<String>()
        val callerThread = Thread.currentThread()
        val callback = object : SynthesisCallback {
            override fun getMaxBufferSize() = 8192
            override fun start(sampleRateInHz: Int, audioFormat: Int, channelCount: Int) = TextToSpeech.SUCCESS
            override fun audioAvailable(buffer: ByteArray, offset: Int, length: Int) = TextToSpeech.SUCCESS
            override fun done(): Int {
                assertEquals(callerThread, Thread.currentThread())
                events.add("done")
                return TextToSpeech.SUCCESS
            }
            override fun error() = error(TextToSpeech.ERROR_SYNTHESIS)
            override fun error(errorCode: Int) {
                assertEquals(callerThread, Thread.currentThread())
                events.add("error")
            }
            override fun hasStarted() = false
            override fun hasFinished() = "done" in events
        }
        // 空文本不需要模型，也能验证框架要求的同步错误与完成回调。
        MossTTSService().onSynthesizeText(SynthesisRequest("", Bundle()), callback)
        assertEquals(listOf("error", "done"), events)
    }
}
