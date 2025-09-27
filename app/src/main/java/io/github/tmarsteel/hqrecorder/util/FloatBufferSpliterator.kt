package io.github.tmarsteel.hqrecorder.util

import java.nio.FloatBuffer
import java.util.Spliterator
import java.util.function.Consumer

class FloatBufferSpliterator(
    private val buffer: FloatBuffer,
    private var position: Int,
    private var limit: Int,
) : Spliterator<Float> {
    override fun characteristics(): Int {
        return Spliterator.SIZED or Spliterator.NONNULL or Spliterator.CONCURRENT
    }

    override fun estimateSize(): Long {
        return (limit - position).toLong()
    }

    override fun tryAdvance(action: Consumer<in Float>): Boolean {
        if (position >= limit) {
            return false
        }

        val float = buffer.get(position)
        position++
        action.accept(float)
        return true
    }

    override fun trySplit(): Spliterator<Float>? {
        if (estimateSize() < 2) {
            return null
        }

        val oldLimit = limit
        limit = limit / 2

        return FloatBufferSpliterator(buffer, limit, oldLimit)
    }
}