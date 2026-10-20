package com.example.nanotranslator

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import kotlinx.coroutines.tasks.await

class AutoLanguageDetector {

    private val identifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.35f)
            .build()
    )

    private val japaneseKanaRegex = Regex("[\\u3040-\\u309F\\u30A0-\\u30FF]")
    private val cjkHanziRegex = Regex("[\\u4E00-\\u9FFF]")
    private val spanishMarkerRegex = Regex("[¿¡ñÑáéíóúÁÉÍÓÚüÜ]")

    suspend fun detectLanguage(text: String, fallback: SupportedLanguage): SupportedLanguage {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return fallback

        if (japaneseKanaRegex.containsMatchIn(cleaned)) {
            return SupportedLanguage.JAPANESE
        }
        if (cjkHanziRegex.containsMatchIn(cleaned)) {
            return SupportedLanguage.CHINESE
        }

        return try {
            val possibleLanguages = identifier.identifyPossibleLanguages(cleaned).await()
            val matched = possibleLanguages
                .asSequence()
                .mapNotNull { candidate -> SupportedLanguage.fromCode(candidate.languageTag) }
                .firstOrNull()

            when {
                matched != null -> matched
                spanishMarkerRegex.containsMatchIn(cleaned) -> SupportedLanguage.SPANISH
                else -> fallback
            }
        } catch (_: Exception) {
            if (spanishMarkerRegex.containsMatchIn(cleaned)) SupportedLanguage.SPANISH else fallback
        }
    }

    fun close() {
        identifier.close()
    }
}
