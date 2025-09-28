package io.github.tmarsteel.hqrecorder.recording

import android.media.AudioDeviceInfo
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
@JvmInline
value class ChannelMask(val mask: Int) : Parcelable {
    fun isSubsetOf(other: ChannelMask): Boolean = (other.mask and this.mask) == this.mask
    operator fun contains(channel: Channel): Boolean = (mask and channel.maskForChannel) == channel.maskForChannel

    val channels: Sequence<Channel> get()= Channel.all.filter { it in this }
    val count: Int get()= mask.countOneBits()

    override fun toString(): String {
        val hwChannelNumbers = channels.toList().sorted()
        return when (hwChannelNumbers.size) {
            1 -> "hardware channel ${hwChannelNumbers.single()}"
            2 -> "hardware channels ${hwChannelNumbers[0]} + ${hwChannelNumbers[1]}"
            else -> "hardware channels ${hwChannelNumbers.joinToString(separator = ", ")}"
        }
    }

    companion object {
        /**
         * @return [AudioDeviceInfo.getChannelMasks], but omitting those that are strict subsets of another mask already in the list
         */
        fun getUniqueMasksFor(audioDevice: AudioDeviceInfo): Set<ChannelMask> {
            val masks = mutableSetOf<ChannelMask>()
            for (intMask in audioDevice.channelMasks.sortedByDescending { it }) {
                val objMask = ChannelMask(intMask)
                if (masks.none { objMask != it && objMask.isSubsetOf(it) }) {
                    masks.add(objMask)
                }
            }

            return masks
        }

        val EMPTY = ChannelMask(0)
    }
}