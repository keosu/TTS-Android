package com.mosstts.edge.protocol

import org.json.JSONArray
import java.io.File
import java.util.Locale
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class EdgeVoice(val name: String, val locale: Locale, val gender: String, val displayName: String) {
    fun supportsLanguage(language: String?): Boolean = language != null &&
        (locale.language.equals(language, true) || runCatching { locale.isO3Language.equals(language, true) }.getOrDefault(false) ||
            (locale.language == "zh" && language.equals("chi", true)))
    fun supportsCountry(country: String?): Boolean = country.isNullOrEmpty() || locale.country.equals(country, true) ||
        runCatching { locale.isO3Country.equals(country, true) }.getOrDefault(false)
    override fun toString(): String = "$displayName · ${if (gender == "Female") "女" else "男"} · $name"
}

class EdgeVoiceCatalog(private val cacheFile: File) {
    @Volatile private var snapshot = FALLBACK
    @Volatile private var cachedModified = -1L

    @Synchronized fun voices(): List<EdgeVoice> {
        if (cacheFile.exists() && cacheFile.lastModified() != cachedModified) {
            runCatching { parse(cacheFile.readText()) }.onSuccess { snapshot = it }
            cachedModified = cacheFile.lastModified()
        }
        return snapshot
    }

    fun refresh(client: EdgeClient): List<EdgeVoice> {
        val json = client.listVoices()
        val updated = parse(json)
        cacheFile.parentFile!!.mkdirs()
        val temporary = Files.createTempFile(cacheFile.parentFile!!.toPath(), "voices-", ".json")
        try {
            Files.write(temporary, json.toByteArray(Charsets.UTF_8))
            Files.move(temporary, cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        synchronized(this) {
            snapshot = updated
            cachedModified = cacheFile.lastModified()
        }
        return updated
    }

    fun find(name: String?) = voices().firstOrNull { it.name == name }
    fun defaultVoice(language: String?, country: String?, preferred: String?): EdgeVoice? {
        val candidates = voices().filter { it.supportsLanguage(language) }
        val regional = candidates.filter { it.supportsCountry(country) }.ifEmpty { candidates }
        return regional.firstOrNull { it.name == preferred }
            ?: regional.firstOrNull { it.name == DEFAULT_VOICE }
            ?: regional.firstOrNull()
    }

    companion object {
        const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"
        // Bootstrapping works offline. Refresh exposes the complete server catalog.
        val FALLBACK = listOf(
            Triple("zh-CN-XiaoxiaoNeural", "Female", "晓晓"), Triple("zh-CN-XiaoyiNeural", "Female", "晓伊"),
            Triple("zh-CN-YunxiNeural", "Male", "云希"), Triple("zh-CN-YunjianNeural", "Male", "云健"),
            Triple("zh-CN-YunxiaNeural", "Male", "云夏"), Triple("zh-CN-YunyangNeural", "Male", "云扬"),
            Triple("zh-TW-HsiaoChenNeural", "Female", "曉臻"), Triple("zh-TW-YunJheNeural", "Male", "雲哲"),
            Triple("zh-HK-HiuMaanNeural", "Female", "曉曼（粤语）"), Triple("zh-HK-WanLungNeural", "Male", "雲龍（粤语）"),
            Triple("en-US-AriaNeural", "Female", "Aria"), Triple("en-US-GuyNeural", "Male", "Guy"),
        ).map { (name, gender, display) -> EdgeVoice(name, Locale.forLanguageTag(name.substringBeforeLast('-')), gender, display) }

        fun parse(json: String): List<EdgeVoice> {
            val data = JSONArray(json)
            val voices = (0 until data.length()).map { i ->
                val value = data.getJSONObject(i)
                val name = value.getString("ShortName")
                require(name.matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid voice name" }
                val locale = Locale.forLanguageTag(value.getString("Locale"))
                require(locale.language.isNotBlank()) { "Invalid voice locale" }
                EdgeVoice(name, locale, value.getString("Gender"),
                    FALLBACK.firstOrNull { it.name == name }?.displayName
                        ?: value.optString("FriendlyName", name))
            }.distinctBy { it.name }.sortedBy { it.name }
            require(voices.isNotEmpty()) { "Empty voice list" }
            return voices
        }
    }
}
