package com.example.nanotranslator

enum class SupportedLanguage(
    val displayName: String,
    val bcp47Tag: String,
    val isoCode: String,
    val promptName: String
) {
    ENGLISH("영어", "en-US", "en", "English"),
    JAPANESE("일본어", "ja-JP", "ja", "Japanese"),
    CHINESE("중국어", "zh-CN", "zh", "Mandarin Chinese"),
    SPANISH("스페인어", "es-ES", "es", "Spanish");

    companion object {
        fun fromCode(code: String?): SupportedLanguage? {
            if (code.isNullOrBlank()) return null
            val normalized = code.lowercase()
            return entries.firstOrNull {
                normalized.startsWith(it.isoCode) || normalized.equals(it.bcp47Tag, ignoreCase = true)
            }
        }
    }
}

sealed interface LanguageSelection {
    data object Auto : LanguageSelection
    data class Manual(val language: SupportedLanguage) : LanguageSelection
}
