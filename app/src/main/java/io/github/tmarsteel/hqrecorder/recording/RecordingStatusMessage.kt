package io.github.tmarsteel.hqrecorder.recording

import android.os.Bundle
import android.os.Message
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class RecordingStatusMessage(
    /**
     * Whether the service is listening to data from the audio device. When the service stops listening, there will be a last
     * status update with this field being false.
     */
    val isListening: Boolean,

    /**
     * Whether the service is saving the listened data to disk.
     */
    val isRecording: Boolean,

    /**
     * while recording: the amount of time spent saving data disk vs the duration of the audio data that was saved, between 0 and 100.
     * When close to 100 the android device is overloaded. There is a high risk of the service missing audio data and corrupting the
     * recorded files.
     */
    val ioLoadPercentage: UInt,

    /**
     * key: [RecordingConfig.InputTrackConfig.id], value: current levels for left and right channel (right is optional)
     */
    val trackLevels: Map<Long, Pair<Float, Float?>>
) : Parcelable {
    companion object {
        const val WHAT_VALUE = 5

        fun buildMessage(message: RecordingStatusMessage): Message {
            val osmessage = Message.obtain(null, WHAT_VALUE)
            osmessage.data = Bundle()
            osmessage.data.putParcelable(null, message)
            return osmessage
        }

        fun fromMessage(message: Message): RecordingStatusMessage? {
            if (message.what != WHAT_VALUE) {
                return null
            }
            return message.data.getParcelable(null, RecordingStatusMessage::class.java)
        }
    }
}