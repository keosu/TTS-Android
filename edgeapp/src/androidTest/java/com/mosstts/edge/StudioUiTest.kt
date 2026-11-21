package com.mosstts.edge

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import androidx.test.espresso.Espresso
import java.io.File

class StudioUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = (if (name == "voices") compose.onNodeWithTag("voice_sheet") else compose.onRoot()).captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), "edge-$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun hideKeyboard() {
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
    }
    @Test fun creationDraftSurvivesNavigationAndActivityRecreation() {
        compose.onNodeWithTag("text_input").performTextReplacement("你好，让文字成为自然流畅的声音。")
        hideKeyboard()
        compose.onNodeWithTag("generate").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("creation_list").performScrollToIndex(0)
        screenshot("creation")
        compose.onNodeWithTag("nav_1").performClick()
        compose.onNodeWithText("还没有生成记录").assertExists()
        screenshot("history")
        compose.onNodeWithTag("nav_2").performClick()
        compose.onNodeWithTag("system_settings").assertExists()
        screenshot("settings")
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("界面主题"))
        compose.onNodeWithText("深色").performClick()
        screenshot("dark")
        compose.onNodeWithText("跟随系统").performClick()
        compose.onNodeWithTag("nav_0").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("text_input").assertTextContains("你好，让文字成为自然流畅的声音。")
    }
    @Test fun voiceSearchSelectsStudioVoiceWithoutChangingSystemDefault() {
        val defaultVoice = EdgeSettings(compose.activity).voice
        compose.onNodeWithTag("choose_voice").performScrollTo().performClick()
        compose.onNodeWithTag("voice_search").performTextInput("Yunxi")
        hideKeyboard()
        compose.onNodeWithTag("voice_zh-CN-YunxiNeural").assertExists()
        screenshot("voices")
        compose.onNodeWithTag("voice_zh-CN-YunxiNeural").performClick()
        compose.onNodeWithTag("voice_search").assertDoesNotExist()
        assertEquals("zh-CN-YunxiNeural", EdgeSettings(compose.activity).studioVoice)
        assertEquals(defaultVoice, EdgeSettings(compose.activity).voice)
    }
}
