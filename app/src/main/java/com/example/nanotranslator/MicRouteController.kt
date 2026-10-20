package com.example.nanotranslator

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

enum class MicPreference { AUTO, PHONE }

data class MicRoute(
    val bluetoothAvailable: Boolean = false,
    val usingBluetooth: Boolean = false,
    val bluetoothName: String? = null,
    val permissionMissing: Boolean = false
)

/**
 * 입력 마이크 선택. 기본(AUTO)은 블루투스 헤드셋이 있으면 그 마이크를, 없으면 휴대폰 마이크를 쓰고,
 * PHONE 이면 헤드셋이 연결돼 있어도 휴대폰 마이크를 씁니다.
 * 블루투스 마이크는 통화용 오디오 경로(SCO / communication device)로 연결해야 입력이 들어옵니다.
 */
class MicRouteController(
    private val context: Context,
    private val onRouteChanged: (MicRoute) -> Unit,
    private val onNeedRestart: () -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    var preference: MicPreference = MicPreference.AUTO
        private set
    private var active = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onDevicesChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onDevicesChanged()
    }

    // API 31 미만에서는 SCO 연결이 비동기라 연결이 끝나면 인식기를 다시 시작해야 합니다.
    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED && active && currentRoute().usingBluetooth) {
                onNeedRestart()
            }
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            ContextCompat.registerReceiver(
                context,
                scoReceiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun isBluetoothInput(type: Int) =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET

    /** 통화용 경로로 연결 가능한 블루투스 마이크 (API 31+ 에서만 정확히 알 수 있음) */
    private fun findCommunicationDevice(): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasBluetoothPermission()) {
            audioManager.availableCommunicationDevices.firstOrNull { isBluetoothInput(it.type) }
        } else null

    private fun bluetoothDevice(): AudioDeviceInfo? {
        findCommunicationDevice()?.let { return it }
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        inputs.firstOrNull { isBluetoothInput(it.type) }?.let { return it }
        // 입력 목록에 아직 없더라도 헤드셋이 연결돼 있으면 사용 가능한 것으로 본다.
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
    }

    fun currentRoute(): MicRoute {
        val bt = bluetoothDevice()
        val permissionMissing = bt != null && !hasBluetoothPermission()
        val use = bt != null && preference == MicPreference.AUTO && !permissionMissing
        val name = bt?.productName?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        return MicRoute(bluetoothAvailable = bt != null, usingBluetooth = use, bluetoothName = name, permissionMissing = permissionMissing)
    }

    fun start() {
        active = true
        applyRouting()
        onRouteChanged(currentRoute())
    }

    fun stop() {
        active = false
        clearRouting()
        onRouteChanged(currentRoute())
    }

    fun setPreference(newPreference: MicPreference) {
        if (preference == newPreference) return
        preference = newPreference
        if (active) {
            applyRouting()
            onNeedRestart()
        }
        onRouteChanged(currentRoute())
    }

    private fun onDevicesChanged() {
        if (active) {
            applyRouting()
            onNeedRestart()
        }
        onRouteChanged(currentRoute())
    }

    private fun applyRouting() {
        if (!active) return
        try {
            if (!currentRoute().usingBluetooth) {
                // 휴대폰 마이크: 특별한 경로를 지정하지 않으면 기본 내장 마이크가 사용됩니다.
                clearRouting()
                return
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                findCommunicationDevice()?.let { audioManager.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                if (!audioManager.isBluetoothScoOn) {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
            }
        } catch (_: SecurityException) {
            clearRouting()
        }
    }

    private fun clearRouting() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                if (audioManager.isBluetoothScoOn) {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                }
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: SecurityException) {
        }
    }

    fun release() {
        active = false
        clearRouting()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            try {
                context.unregisterReceiver(scoReceiver)
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
