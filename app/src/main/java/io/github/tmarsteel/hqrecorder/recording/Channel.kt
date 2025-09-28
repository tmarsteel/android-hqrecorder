package io.github.tmarsteel.hqrecorder.recording

import android.media.AudioFormat
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
@JvmInline
value class Channel(val number: Int) : Comparable<Channel>, Parcelable {
    init {
        require(number in 1..32) {
            "Channels numbers must be in the range [1; 32]"
        }
    }

    override fun compareTo(other: Channel): Int {
        return number.compareTo(other.number)
    }

    fun coerceAtLeast(other: Channel?): Channel {
        return Channel(number.coerceAtLeast(other?.number ?: 0))
    }

    override fun toString(): String = number.toString()

    val maskForChannel: Int get()= 1 shl number

    companion object {
        val FIRST = Channel(1)
        val SECOND = Channel(2)

        val all: Sequence<Channel> = sequence {
            for (bit in 0..31) {
                yield(Channel(bit + 1))
            }
        }

        /**
         * Builds a value of the format as [android.media.AudioFormat.getChannelIndexMask].
         */
        fun buildIndexMask(channels: Iterable<Channel>): Int {
            var mask = 0
            for (ch in channels) {
                mask = mask or ch.maskForChannel
            }

            return mask
        }
    }
}