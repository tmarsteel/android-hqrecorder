package io.github.tmarsteel.hqrecorder.recording

import android.media.AudioFormat
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
@JvmInline
value class Channel(val number: Int) : Comparable<Channel>, Parcelable {
    init {
        require(number in NUMBER_RANGE) {
            "Channels numbers must be in the range $NUMBER_RANGE"
        }
    }

    override fun compareTo(other: Channel): Int {
        return number.compareTo(other.number)
    }

    fun coerceAtLeast(other: Channel?): Channel {
        return Channel(number.coerceAtLeast(other?.number ?: 1))
    }

    override fun toString(): String = number.toString()

    val maskForChannel: Int get()= 1 shl (number - 1)

    companion object {
        val NUMBER_RANGE = 1..32
        val FIRST = Channel(1)

        val all: Sequence<Channel> = sequence {
            for (number in 1..32) {
                yield(Channel(number))
            }
        }
    }
}