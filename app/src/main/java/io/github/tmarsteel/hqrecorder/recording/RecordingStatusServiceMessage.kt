package io.github.tmarsteel.hqrecorder.recording

import android.os.Bundle
import android.os.Message
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlin.time.Duration

@Parcelize
data class RecordingStatusServiceMessage(
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
    val loadPercentage: UInt,

    /**
     * key: [RecordingConfig.InputTrackConfig.id], value: current levels for left and right channel (right is optional)
     */
    val trackLevels: Map<Long, Pair<Float, Float?>>,

    /**
     * The amount of time/audio data recorded into the current take, or 0 if not [isRecording]
     */
    val currentTakeDuration: Duration,
) : Parcelable {
    companion object {
        const val WHAT_VALUE = 5

        fun buildMessage(message: RecordingStatusServiceMessage): Message {
            val osmessage = Message.obtain(null, WHAT_VALUE)
            osmessage.data = Bundle()
            osmessage.data.putParcelable(null, message)
            return osmessage
        }

        fun fromMessage(message: Message): RecordingStatusServiceMessage? {
            if (message.what != WHAT_VALUE) {
                return null
            }
            return message.data.getParcelable(null, RecordingStatusServiceMessage::class.java)
        }
    }
}