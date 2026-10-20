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
import java.util.concurrent.atomic.AtomicInteger

class AudioRouteController(
    context: Context,
    private val onTtsStateChanged: (isSpeaking: Boolean) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val pending = AtomicInteger(0)

    /** 아직 읽지 못한 문장 수 */
    val backlog: Int get() = pending.get()

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
                    override fun onDone(utteranceId: String?) = finishOne()
                    override fun onStop(utteranceId: String?, interrupted: Boolean) = finishOne()

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finishOne()
                })
                isTtsReady = true
            }
        }
    }

    fun isBluetoothOutputConnected(): Boolean {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
    }

    private fun finishOne() {
        val left = pending.updateAndGet { if (it > 0) it - 1 else 0 }
        onTtsStateChanged(left > 0)
    }

    fun enqueueKoreanSpeech(koreanChunk: String) {
        if (!isTtsReady || koreanChunk.isBlank()) return
        val queued = pending.get()
        // 너무 밀리면 오래된 내용을 읽는 것보다 건너뛰는 편이 낫다 (자막은 그대로 표시됨)
        if (queued >= MAX_BACKLOG) return
        // 밀린 만큼 읽는 속도를 올려 따라잡는다
        tts?.setSpeechRate(
            when {
                queued >= 4 -> 1.7f
                queued >= 2 -> 1.4f
                else -> 1.15f
            }
        )
        pending.incrementAndGet()
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
        pending.set(0)
        tts?.stop()
        onTtsStateChanged(false)
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
    }

    private companion object {
        const val MAX_BACKLOG = 6
    }
}
