package io.github.tmarsteel.hqrecorder.recording

@JvmInline
value class Channel(val number: UInt) : Comparable<Channel> {
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

    companion object {
        val FIRST = Channel(1u)
        val SECOND = Channel(2u)
    }
}