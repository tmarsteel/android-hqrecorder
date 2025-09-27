package io.github.tmarsteel.hqrecorder.util

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import java.nio.FloatBuffer

val AudioFormat.minBufferSizeInBytes: Int get()= AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)

fun <R> FloatBuffer.fold(initial: R, accumulate: (R, Float) -> R): R {
    var result = initial
    while (hasRemaining()) {
        result = accumulate(result, get())
    }

    return result
}

fun FloatBuffer.any(predicate: (Float) -> Boolean): Boolean {
    val initialPosition = position()
    val initialLimit = limit()
    var result = false
    while (hasRemaining()) {
        if (predicate(get())) {
            result = true
            break
        }
    }

    position(initialPosition)
    limit(initialLimit)
    return result
}

val AudioDeviceInfo.humanLabel: String get() {
    val typeStr = when (type) {
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM radio receiver"
        AudioDeviceInfo.TYPE_IP,
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "networked"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset Microphone"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line-In (analog)"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line-In (digital)"
        AudioDeviceInfo.TYPE_AUX_LINE -> "Aux-In"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC, -> "HDMI"
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
        AudioDeviceInfo.TYPE_DOCK -> "Dock (digital)"
        AudioDeviceInfo.TYPE_DOCK_ANALOG -> "Dock (digital)"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin microphone"
        AudioDeviceInfo.TYPE_TV_TUNER -> "TV signal"
        AudioDeviceInfo.TYPE_TELEPHONY -> "call audio"
        AudioDeviceInfo.TYPE_BUS -> "other external"
        else -> null
    }
    return "$productName (${typeStr ?: address})"
}