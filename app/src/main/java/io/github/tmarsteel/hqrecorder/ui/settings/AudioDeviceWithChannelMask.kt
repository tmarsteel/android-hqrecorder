package io.github.tmarsteel.hqrecorder.ui.settings

import android.media.AudioDeviceInfo
import io.github.tmarsteel.hqrecorder.recording.ChannelMask
import io.github.tmarsteel.hqrecorder.util.humanLabel

/**
 * A combination of an [AudioDeviceInfo] together with one if its valid channel masks (see [AudioDeviceInfo.getChannelMasks])
 */
data class AudioDeviceWithChannelMask(
    val device: AudioDeviceInfo,
    val channelMask: ChannelMask,
) : Comparable<AudioDeviceWithChannelMask> {
    val combinedId: Long = (device.id.toLong() shl 31) or channelMask.mask.toLong()

    override fun toString(): String {
        return "${device.humanLabel} $channelMask"
    }

    override fun compareTo(other: AudioDeviceWithChannelMask): Int {
        val c = this.device.id compareTo other.device.id
        if (c != 0) {
            return c
        }
        return channelMask.channels.min().compareTo(other.channelMask.channels.min())
    }
}