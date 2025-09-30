package io.github.tmarsteel.hqrecorder.recording

import android.media.AudioFormat
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class RecordingConfig(
    /** [android.media.AudioDeviceInfo.getAddress] of the target device */
    val deviceAddress: String,
    /** [android.media.AudioDeviceInfo.getId] of the target device */
    val deviceId: Int,
    val channelMask: ChannelMask,
    /**
     * @see android.media.AudioFormat.getSampleRate
     * @see android.media.AudioRecord.getSampleRate
     * @see android.media.AudioDeviceInfo.getSampleRates
     */
    val samplingRate: Int,
    /**
     * @see android.media.AudioFormat.getEncoding
     */
    val encoding: Int,
    val tracks: List<InputTrackConfig>
) : Parcelable {
    @Parcelize
    data class InputTrackConfig(
        /**
         * must be unique among all tracks in [io.github.tmarsteel.hqrecorder.recording.RecordingConfig.tracks]
         */
        val id: Long,

        /**
         * human-readable label for this track, e.g. "guitar", "vocals", "basedrum", ...
         */
        val label: String,

        /**
         * The channel of the selected device to use for the left channel of this track/
         * or as the single channel for mono recording.
         */
        val leftOrMonoDeviceChannel: Channel,

        /**
         * The channel of the selected device to use for the right channel of this track.
         */
        val rightDeviceChannel: Channel?,
    ) : Parcelable

    companion object {
        val INITIAL = RecordingConfig(
            "",
            0,
            ChannelMask.EMPTY,
            44100,
            AudioFormat.ENCODING_PCM_16BIT,
            emptyList(),
        )
    }
}