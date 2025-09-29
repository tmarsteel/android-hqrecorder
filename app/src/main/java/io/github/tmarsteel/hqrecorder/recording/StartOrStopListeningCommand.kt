package io.github.tmarsteel.hqrecorder.recording

import android.os.Bundle
import android.os.Message
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * The recording service should start reading data from the configured device (see [UpdateRecordingConfigCommand]),
 * though not yet save to disk. The service will start to publish []
 */
@Parcelize
class StartOrStopListeningCommand(
    /**
     * if true, assures listening is started; if false, assures its stopped
     */
    val start: Boolean,
    /**
     * if true, assures the [Message.replyTo] [android.os.Messenger] will receive [RecordingStatusServiceMessage]s; if false,
     * assures the [Message.replyTo] no longer receives these updates.
     */
    val statusSubscription: Boolean,
) : Parcelable {
    companion object {
        /** @see Message.what */
        const val WHAT_VALUE = 7

        fun buildMessage(start: Boolean, statusSubscription: Boolean): Message {
            val message = Message.obtain(null, WHAT_VALUE)
            message.data = Bundle()
            message.data.putParcelable(null, StartOrStopListeningCommand(start, statusSubscription))
            return message
        }

        fun fromMessage(message: Message): StartOrStopListeningCommand? {
            if (message.what != WHAT_VALUE) {
                return null
            }
            return message.data.getParcelable(null, StartOrStopListeningCommand::class.java)
        }
    }

    @Parcelize
    data class Response(val result: Result) : Parcelable {
        enum class Result {
            /**
             * the service is receiving audio data from the device
             */
            LISTENING,

            /**
             * the service is no longer receiving audio data from the device
             */
            NOT_LISTENING,

            /**
             * could not acquire access to the audio device
             */
            DEVICE_NOT_AVAILABLE,

            /**
             * Permission [android.Manifest.permission.RECORD_AUDIO] is not granted
             */
            NO_PERMISSION,

            /**
             * no config has been set yet, send a [UpdateRecordingConfigCommand]
             */
            NOT_CONFIGURED,

            /**
             * Data is currently being recorded, send a [FinishTakeCommand] first
             */
            STILL_RECORDING,

            ;
        }

        companion object {
            const val WHAT_VALUE = 8

            fun buildMessage(response: Response): Message {
                val message = Message.obtain(null, WHAT_VALUE)
                message.data = Bundle()
                message.data.putParcelable(null, response)
                return message
            }

            fun fromMessage(message: Message): Response? {
                if (message.what != WHAT_VALUE) {
                    return null
                }
                return message.data.getParcelable(null, Response::class.java)
            }
        }
    }
}