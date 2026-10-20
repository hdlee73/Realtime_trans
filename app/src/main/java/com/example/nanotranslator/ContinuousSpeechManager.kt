package com.example.nanotranslator

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class ContinuousSpeechManager(
    private val context: Context,
    private val onPartialTranscript: (text: String, activeLang: SupportedLanguage) -> Unit,
    private val onFinalSegment: (text: String, audioDetectedLang: SupportedLanguage?) -> Unit,
    private val onLanguageSwitched: (SupportedLanguage) -> Unit,
    private val onErrorInfo: (String) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListeningActive = false

    private var selectionMode: LanguageSelection = LanguageSelection.Auto
    private var currentPrimaryLang: SupportedLanguage = SupportedLanguage.ENGLISH

    fun startListening(
        selection: LanguageSelection,
        initialPrimary: SupportedLanguage = currentPrimaryLang,
        preferOnDevice: Boolean = true
    ) {
        selectionMode = selection
        currentPrimaryLang = when (selection) {
            is LanguageSelection.Auto -> initialPrimary
            is LanguageSelection.Manual -> selection.language
        }
        isListeningActive = true
        mainHandler.post { initAndStart(preferOnDevice) }
    }

    fun updateAutoPrimaryLanguage(detected: SupportedLanguage) {
        if (selectionMode is LanguageSelection.Auto && currentPrimaryLang != detected) {
            currentPrimaryLang = detected
            onLanguageSwitched(detected)
        }
    }

    fun stopListening() {
        isListeningActive = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }

    private fun createRecognizer(preferOnDevice: Boolean): SpeechRecognizer {
        if (preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    private fun initAndStart(preferOnDevice: Boolean) {
        if (!isListeningActive) return

        speechRecognizer?.destroy()
        speechRecognizer = createRecognizer(preferOnDevice)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentPrimaryLang.bcp47Tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 450L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 400L)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 한 번 켜 두면 문장 사이에 인식기를 다시 만들지 않고 계속 듣는다 (사이에 놓치는 말 방지)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val allowed = ArrayList(SupportedLanguage.entries.map { it.bcp47Tag })
                if (selectionMode is LanguageSelection.Auto) {
                    putExtra(
                        RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                        RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE
                    )
                    putStringArrayListExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                        allowed
                    )
                }
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                    allowed
                )
            }
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: Bundle?) {
                extractDetectedAudioLanguage(partialResults)?.let { detected ->
                    if (selectionMode is LanguageSelection.Auto && detected != currentPrimaryLang) {
                        currentPrimaryLang = detected
                        onLanguageSwitched(detected)
                    }
                }

                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    onPartialTranscript(text, currentPrimaryLang)
                }
            }

            private fun handleFinal(results: Bundle?) {
                val audioLang = extractDetectedAudioLanguage(results)
                if (selectionMode is LanguageSelection.Auto && audioLang != null && audioLang != currentPrimaryLang) {
                    currentPrimaryLang = audioLang
                    onLanguageSwitched(audioLang)
                }

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val finalSentence = matches?.firstOrNull().orEmpty()
                if (finalSentence.isNotBlank()) {
                    onFinalSegment(finalSentence, audioLang)
                }
            }

            // 연속(분할) 세션이 아닌 경우의 최종 결과: 처리 후 인식기를 다시 시작
            override fun onResults(results: Bundle?) {
                handleFinal(results)
                restartLoop(preferOnDevice)
            }

            // 연속 세션: 인식기를 끄지 않고 문장(구간)별 결과가 계속 들어온다
            override fun onSegmentResults(segmentResults: Bundle) {
                handleFinal(segmentResults)
            }

            override fun onEndOfSegmentedSession() {
                restartLoop(preferOnDevice)
            }

            override fun onError(error: Int) {
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    onErrorInfo("STT 상태 코드: $error")
                }
                // 온디바이스 인식기를 쓸 수 없는 언어/기기면 일반 인식기로 재시도
                val fallbackToCloud = preferOnDevice &&
                    (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
                restartLoop(if (fallbackToCloud) false else preferOnDevice)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    private fun extractDetectedAudioLanguage(bundle: Bundle?): SupportedLanguage? {
        if (bundle == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val topLocale = bundle.getString(SpeechRecognizer.DETECTED_LANGUAGE)
            ?: bundle.getStringArrayList(SpeechRecognizer.TOP_LOCALE_ALTERNATIVES)?.firstOrNull()
        return SupportedLanguage.fromCode(topLocale)
    }

    private fun restartLoop(preferOnDevice: Boolean) {
        if (!isListeningActive) return
        mainHandler.postDelayed({
            if (isListeningActive) initAndStart(preferOnDevice)
        }, 60L)
    }
}
