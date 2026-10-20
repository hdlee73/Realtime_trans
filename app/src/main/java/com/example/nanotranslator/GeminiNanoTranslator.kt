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

        val langName = detectedLang?.promptName ?: "English, Japanese, Mandarin Chinese, or Spanish"
        // 요청마다 처리해야 하는 프롬프트가 길수록 느려지므로 최소한으로 유지한다.
        val prompt = buildString {
            append("Interpret the $langName speech into natural polite spoken Korean (해요체). ")
            append("Keep who is speaking and who is addressed (I = speaker, you = listener); questions stay questions. ")
            append("Output ONLY the Korean, one line per sentence.\n")
            append("English: How old are you?\nKorean: 몇 살이세요?\n")
            append("$langName: ${sourceText.trim()}\nKorean:")
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
