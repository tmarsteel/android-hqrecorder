package io.github.tmarsteel.hqrecorder.recording

import android.media.AudioFormat
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
@JvmInline
value class Channel(val number: UInt) : Comparable<Channel>, Parcelable {
    init {
        assert(number > 0u) {
            "Channels start at 1!"
        }
    }

    override fun compareTo(other: Channel): Int {
        return number.compareTo(other.number)
    }

    fun coerceAtLeast(other: Channel?): Channel {
        return Channel(number.coerceAtLeast(other?.number ?: 0u))
    }

    fun next(): Channel {
        return Channel(number + 1u)
    }

    val maskForChannel: Int get()= 1 shr number.toInt()

    companion object {
        val FIRST = Channel(1u)
        val SECOND = Channel(2u)

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

        /**
         * @return a map from channel to index within a frame where the sample for the channel can be found.
         */
        fun getChannelToSampleIndexMapping(format: AudioFormat): Map<Channel, Int> {
            val indexMask = format.channelIndexMask
            if (indexMask == AudioFormat.CHANNEL_INVALID) {
                // TODO!!!
                return mapOf(Channel.FIRST to 0)
            }
            var index = 0
            var channel = FIRST
            val map = HashMap<Channel, Int>()
            while (channel.number <= 32u) {
                if (indexMask and channel.maskForChannel != 0) {
                    map[channel] = index
                    index++
                }
                channel = channel.next()
            }

            return map
        }
    }
}