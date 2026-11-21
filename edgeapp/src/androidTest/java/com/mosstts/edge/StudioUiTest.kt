package com.mosstts.edge

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import androidx.test.espresso.Espresso

class StudioUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = (if (name == "voices") compose.onNodeWithTag("voice_sheet") else compose.onRoot()).captureToImage().asAndroidBitmap()
        saveUiScreenshot(name, image)
    }
    private fun hideKeyboard() {
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
    }
    @Test fun creationDraftSurvivesNavigationAndActivityRecreation() {
        compose.onNodeWithTag("text_input").performTextReplacement("你好，让文字成为自然流畅的声音。")
        hideKeyboard()
        compose.onNodeWithTag("creation_list").performScrollToNode(hasTestTag("generate"))
        compose.onNodeWithTag("generate").assertIsEnabled()
        screenshot("creation-controls")
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
        compose.onNodeWithTag("creation_list").performScrollToNode(hasTestTag("choose_voice"))
        compose.onNodeWithTag("choose_voice").performClick()
        screenshot("voices")
        compose.onNodeWithTag("voice_search").performTextInput("Yunxi")
        hideKeyboard()
        compose.onNodeWithTag("voice_zh-CN-YunxiNeural").assertExists()
        compose.onNodeWithTag("voice_zh-CN-YunxiNeural").performClick()
        compose.onNodeWithTag("voice_search").assertDoesNotExist()
        assertEquals("zh-CN-YunxiNeural", EdgeSettings(compose.activity).studioVoice)
        assertEquals(defaultVoice, EdgeSettings(compose.activity).voice)
    }
}
