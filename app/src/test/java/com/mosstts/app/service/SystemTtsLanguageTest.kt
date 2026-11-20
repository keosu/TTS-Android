package com.mosstts.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTtsLanguageTest {
    @Test
    fun acceptsIsoTwoAndThreeLetterChineseCodes() {
        for (language in listOf("zh", "zho", "chi", "ZH", "ZHO")) {
            assertTrue(language, SystemTtsLanguage.supportsLanguage(language))
        }
        assertFalse(SystemTtsLanguage.supportsLanguage("en"))
        assertFalse(SystemTtsLanguage.supportsLanguage(null))
    }

    @Test
    fun acceptsCountryCodesSentByTheAndroidFramework() {
        for (country in listOf(null, "", "CN", "CHN", "chn")) {
            assertTrue(SystemTtsLanguage.supportsCountry(country))
        }
        assertFalse(SystemTtsLanguage.supportsCountry("US"))
    }

    @Test
    fun dataCheckUsesTheSameIsoCodesAsTheDefaultVoice() {
        val locale = SystemTtsLanguage.locale
        assertEquals(SystemTtsLanguage.VOICE_NAME, locale.toLanguageTag())
        assertTrue(SystemTtsLanguage.dataVoiceNames.contains(locale.isO3Language))
        assertTrue(
            SystemTtsLanguage.dataVoiceNames.contains("${locale.isO3Language}-${locale.isO3Country}"),
        )
    }
}
