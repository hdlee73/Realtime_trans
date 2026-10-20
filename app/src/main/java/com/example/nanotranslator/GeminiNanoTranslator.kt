package com.example.nanotranslator

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class GeminiNanoTranslator {

    private val generativeModel: GenerativeModel = Generation.getClient()
    private val sentenceSplitRegex = Regex("(?<=[,.?!。，？！\\n])")

    suspend fun prepareModel(onStatusChanged: (String) -> Unit): Boolean {
        return try {
            when (generativeModel.checkStatus()) {
                FeatureStatus.AVAILABLE -> {
                    onStatusChanged("온디바이스 Gemini Nano 준비 완료")
                    true
                }
                FeatureStatus.DOWNLOADABLE -> {
                    onStatusChanged("Gemini Nano 모델 다운로드 시작...")
                    var success = false
                    generativeModel.download().collect { status ->
                        when (status) {
                            is DownloadStatus.DownloadProgress -> {
                                val mb = status.totalBytesDownloaded / (1024 * 1024)
                                onStatusChanged("Gemini Nano 다운로드 중... (${mb}MB)")
                            }
                            is DownloadStatus.DownloadCompleted -> {
                                onStatusChanged("Gemini Nano 다운로드 완료")
                                success = true
                            }
                            is DownloadStatus.DownloadFailed -> {
                                onStatusChanged("다운로드 실패: ${status.e.message}")
                            }
                            else -> {}
                        }
                    }
                    success
                }
                FeatureStatus.DOWNLOADING -> {
                    onStatusChanged("Gemini Nano 백그라운드 다운로드 중...")
                    false
                }
                else -> {
                    onStatusChanged("이 기기는 Gemini Nano(AICore)를 지원하지 않습니다.")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e("GeminiNano", "모델 상태 확인 실패", e)
            onStatusChanged("AICore 초기화 오류: ${e.localizedMessage}")
            false
        }
    }

    fun translateStreamToKorean(
        sourceText: String,
        detectedLang: SupportedLanguage?,
        onSpeakableChunk: (String) -> Unit
    ): Flow<String> = flow {
        if (sourceText.isBlank()) return@flow

        val langHint = detectedLang?.promptName ?: "English, Japanese, Mandarin Chinese, or Spanish"
        val prompt = buildString {
            append("Auto-detect the language ($langHint) of the following speech and translate it into natural spoken Korean. ")
            append("Output ONLY the Korean translation without quotes or notes:\n")
            append(sourceText)
        }

        val fullTranslation = StringBuilder()
        val chunkBuffer = StringBuilder()

        generativeModel.generateContentStream(prompt).collect { response ->
            val piece = response.candidates.firstOrNull()?.text.orEmpty()
            if (piece.isNotEmpty()) {
                fullTranslation.append(piece)
                chunkBuffer.append(piece)
                emit(fullTranslation.toString())

                if (sentenceSplitRegex.containsMatchIn(chunkBuffer) ||
                    (chunkBuffer.length >= 18 && piece.endsWith(" "))
                ) {
                    val toSpeak = chunkBuffer.toString().trim()
                    if (toSpeak.isNotEmpty()) {
                        onSpeakableChunk(toSpeak)
                        chunkBuffer.clear()
                    }
                }
            }
        }

        val remaining = chunkBuffer.toString().trim()
        if (remaining.isNotEmpty()) {
            onSpeakableChunk(remaining)
        }
    }

    fun close() {
        generativeModel.close()
    }
}
