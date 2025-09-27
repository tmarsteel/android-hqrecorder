package io.github.tmarsteel.hqrecorder.recording

data class RecordingConfig(
    /** [android.media.AudioDeviceInfo.getAddress] of the target device */
    var deviceAddress: String,
    /** [android.media.AudioDeviceInfo.getId] of the target device */
    var deviceId: Int,
    /**
     * @see android.media.AudioFormat.getSampleRate
     * @see android.media.AudioRecord.getSampleRate
     * @see android.media.AudioDeviceInfo.getSampleRates
     */
    var samplingRate: Int,
    /**
     * @see android.media.AudioFormat.getEncoding
     */
    var encoding: Int,
    var tracks: List<InputTrackConfig>
) {
    data class InputTrackConfig(
        /**
         * must be unique among all tracks in [io.github.tmarsteel.hqrecorder.recording.RecordingConfig.tracks]
         */
        var id: Long,

        /**
         * human-readable label for this track, e.g. "guitar", "vocals", "basedrum", ...
         */
        var label: String,

        /**
         * The channel of the selected device to use for the left channel of this track/
         * or as the single channel for mono recording.
         */
        var leftOrMonoDeviceChannel: Channel,

        /**
         * The channel of the selected device to use for the right channel of this track.
         */
        var rightDeviceChannel: Channel?,
    )
}