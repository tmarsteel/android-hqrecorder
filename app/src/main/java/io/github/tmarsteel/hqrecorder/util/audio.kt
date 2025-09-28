package io.github.tmarsteel.hqrecorder.util

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import io.github.tmarsteel.hqrecorder.ui.settings.AudioDeviceWithChannelMask
import java.nio.FloatBuffer
import java.text.DecimalFormat
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiConsumer
import java.util.function.BinaryOperator
import java.util.function.Function
import java.util.function.Supplier
import java.util.stream.Collector
import java.util.stream.Stream
import java.util.stream.StreamSupport
import kotlin.math.absoluteValue
import kotlin.math.log10

val AudioFormat.minBufferSizeInBytes: Int get()= AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
val AudioFormat.bytesPerSecond: Int get() = frameSizeInBytes * sampleRate

fun <R> FloatBuffer.fold(initial: R, accumulate: (R, Float) -> R): R {
    var result = initial
    while (hasRemaining()) {
        result = accumulate(result, get())
    }

    return result
}

fun FloatBuffer.stream(): Stream<Float> {
    return StreamSupport.stream(FloatBufferSpliterator(this, position(), limit()), false)
}

fun accumulatingFloat(combiner: (Float, Float) -> Float): Collector<Float, *, Float> = object : Collector<Float, AtomicReference<Float?>, Float> {
    override fun accumulator(): BiConsumer<AtomicReference<Float?>, Float> = BiConsumer { acc, v ->
        val accV = acc.getPlain()
        if (accV == null) {
            acc.setPlain(v)
        } else {
            acc.setPlain(combiner(accV, v))
        }
    }

    override fun characteristics(): Set<Collector.Characteristics> =setOf(Collector.Characteristics.CONCURRENT, Collector.Characteristics.UNORDERED)

    override fun combiner(): BinaryOperator<AtomicReference<Float?>> = BinaryOperator { a, b ->
        val aV = a.getPlain()
        val bV = a.getPlain()
        if (aV == null || bV == null) b else {
            AtomicReference(combiner(aV, bV))
        }
    }

    override fun finisher(): Function<AtomicReference<Float?>, Float> = Function { acc -> acc.getPlain() ?: 0.0f }
    override fun supplier(): Supplier<AtomicReference<Float?>> = Supplier { AtomicReference<Float?>(null) }
}

data object FloatCollectors {
    val AVERAGING = accumulatingFloat { a, b -> a / 2.0f + b / 2.0f }
    val MAXING = accumulatingFloat { a, b -> a.coerceAtLeast(b) }
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
    val typeAndOrAddressStr = when {
        typeStr == null && address.isEmpty() -> ""
        typeStr == null -> " ($address)"
        address.isEmpty() -> " ($typeStr)"
        else -> " ($typeStr $address)"
    }
    return "$productName${typeAndOrAddressStr}"
}

private val decibelNumberFormat = (DecimalFormat.getNumberInstance() as DecimalFormat).also {
    it.applyPattern("#.#dB")
}
fun getSampleLevelAsDecibelText(sample: Float): String {
    val absSample = sample.absoluteValue
    if (absSample <= 0.0f) {
        return "-inf"
    }
    if (absSample == Float.MAX_VALUE) {
        return "-0dB"
    }

    val level = log10(absSample.toDouble() / Float.MAX_VALUE.toDouble()) * 10.0
    return decibelNumberFormat.format(level)
    // 3.3525738E38
}