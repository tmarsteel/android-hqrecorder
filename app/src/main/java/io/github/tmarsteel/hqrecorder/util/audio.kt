package io.github.tmarsteel.hqrecorder.util

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import io.github.tmarsteel.hqrecorder.recording.TakeRecorderRunnable
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
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
    val decibels = sample.absoluteValue.getRelationToInDecibels(Float.MAX_VALUE).coerceAtMost(0.0f)
    if (decibels == Float.NEGATIVE_INFINITY) {
        return "-inf"
    }
    if (decibels == 0.0f) {
        return "-0.0dB"
    }

    return decibelNumberFormat.format(decibels)
}

fun Float.getRelationToInDecibels(other: Float): Float {
    return (log10(toDouble() / other.toDouble()) * 20.0).toFloat()
}

/**
 * reads one sample of the size [sampleSizeInBytes] from [src] starting at [off] and converts it to a sample of the format
 * [AudioFormat.ENCODING_PCM_FLOAT].
 * @param encoding see [AudioFormat.getEncoding]
 */
fun convertSampleToFloat32(src: ByteArray, off: Int, sampleSizeInBytes: Int, encoding: Int): Float {
    return when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> {
            assert(sampleSizeInBytes == 1)
            (src[off].toFloat().absoluteValue / Byte.MAX_VALUE.toFloat()) * Float.MAX_VALUE
        }
        AudioFormat.ENCODING_PCM_16BIT -> {
            assert(sampleSizeInBytes == 2)
            // the input data is LE, but java runs on BE
            val byte0 = src[off + 0].toInt() and 0xFF
            val byte1 = src[off + 1].toInt() and 0xFF
            val value = ((byte1 shl 8) or byte0).toShort()
            (value.toFloat().absoluteValue / (Short.MAX_VALUE.toFloat())) * Float.MAX_VALUE
        }
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            assert(sampleSizeInBytes == 2)
            // the input data is LE, but java runs on BE
            val byte0 = src[off + 0].toInt() and 0xFF
            val byte1 = src[off + 1].toInt() and 0xFF
            val byte2 = src[off + 2].toInt() and 0xFF
            val value = (byte2 shl 16) or (byte1 shl 8) or byte0
            (value.toFloat().absoluteValue / TakeRecorderRunnable.Companion.MAX_SAMPLE_24BIT_INT) * Float.MAX_VALUE
        }
        AudioFormat.ENCODING_PCM_32BIT -> {
            // the input data is LE, but java runs on BE
            val byte0 = src[off + 0].toInt() and 0xFF
            val byte1 = src[off + 1].toInt() and 0xFF
            val byte2 = src[off + 2].toInt() and 0xFF
            val byte3 = src[off + 3].toInt() and 0xFF
            val value = (byte3 shl 24) or (byte2 shl 16) or (byte1 shl 8) or byte0
            ((value.toDouble().absoluteValue / TakeRecorderRunnable.Companion.MAX_SAMPLE_32BIT_INT) * Float.MAX_VALUE.toDouble()).toFloat()
        }
        AudioFormat.ENCODING_PCM_FLOAT -> {
            val byte0 = src[off + 0].toInt() and 0xFF
            val byte1 = src[off + 1].toInt() and 0xFF
            val byte2 = src[off + 2].toInt() and 0xFF
            val byte3 = src[off + 3].toInt() and 0xFF
            Float.fromBits((byte3 shl 24) or (byte2 shl 16) or (byte1 shl 8) or byte0)
        }
        else -> throw RuntimeException("unsupported format")
    }
}

/**
 * Given a buffer that points to the start of a frame where a single sample is [sampleSizeInBytes] long,
 * writes the bytes that correspond to the sample with index [sampleIndexInFrame] to [dst], starting at index [dstOff].
 * @param sampleSizeInBytes see [AudioFormat.getEncoding] and [TakeRecorderRunnable.Companion.SAMPLE_SIZE_IN_BYTES_BY_ENCODING]
 * @param dst should be 4 bytes of size to be able to accommodate all encodings
 */
fun extractSampleBytes(
    compoundFrame: ByteBuffer,
    sampleSizeInBytes: Int,
    sampleIndexInFrame: Int,
    dst: ByteArray,
    dstOff: Int,
) {
    val indexOfFirst = sampleIndexInFrame * sampleSizeInBytes
    val indexOfLast = indexOfFirst + sampleSizeInBytes - 1
    if (compoundFrame.remaining() < indexOfLast) {
        throw BufferUnderflowException()
    }

    for (byteIndex in 0 until sampleSizeInBytes) {
        dst[dstOff + byteIndex] = compoundFrame.get(compoundFrame.position() + indexOfFirst + byteIndex)
    }
}