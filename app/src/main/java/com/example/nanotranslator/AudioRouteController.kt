package com.example.nanotranslator

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

class AudioRouteController(
    context: Context,
    private val onTtsStateChanged: (isSpeaking: Boolean) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                tts?.setSpeechRate(1.12f)

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = onTtsStateChanged(true)
                    override fun onDone(utteranceId: String?) = onTtsStateChanged(false)

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = onTtsStateChanged(false)
                })
                isTtsReady = true
            }
        }
    }

    fun isBluetoothOutputConnected(): Boolean {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
    }

    fun enqueueKoreanSpeech(koreanChunk: String) {
        if (!isTtsReady || koreanChunk.isBlank()) return
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(
            koreanChunk,
            TextToSpeech.QUEUE_ADD,
            params,
            UUID.randomUUID().toString()
        )
    }

    fun stopAll() {
        tts?.stop()
        onTtsStateChanged(false)
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
    }
}
