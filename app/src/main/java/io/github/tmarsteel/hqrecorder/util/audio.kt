package io.github.tmarsteel.hqrecorder.util

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