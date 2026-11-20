package com.mosstts.app.service

import java.util.Locale

/** 系统 TTS 使用 ISO 三字母语言/国家码，应用也接受常用的两字母码。 */
internal object SystemTtsLanguage {
    val locale: Locale = Locale.CHINA
    const val VOICE_NAME = "zh-CN"
    val dataVoiceNames: List<String> = listOf("zho", "zho-CHN")

    fun supportsLanguage(language: String?): Boolean =
        language.equals("zh", ignoreCase = true) ||
            language.equals("zho", ignoreCase = true) ||
            language.equals("chi", ignoreCase = true)

    fun supportsCountry(country: String?): Boolean =
        country.isNullOrEmpty() || country.equals("CN", ignoreCase = true) ||
            country.equals("CHN", ignoreCase = true)
}
