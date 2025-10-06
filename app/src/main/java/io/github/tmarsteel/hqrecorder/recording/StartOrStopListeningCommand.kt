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
data class StartOrStopListeningCommand(
    val listen: ListeningAction,

    /**
     * subscribe or unsubscribe the [Message.replyTo] [android.os.Messenger] from [RecordingStatusServiceMessage]s
     */
    val statusUpdates: SubscriptionAction,
) : Parcelable {
    enum class ListeningAction {
        START,
        STOP,
        ;
    }

    enum class SubscriptionAction {
        SUBSCRIBE,
        UNSUBSCRIBE,
        NO_ACTION,
        ;
    }

    companion object {
        /** @see Message.what */
        const val WHAT_VALUE = 7

        fun buildMessage(listen: ListeningAction, statusUpdates: SubscriptionAction): Message {
            val message = Message.obtain(null, WHAT_VALUE)
            message.data = Bundle()
            message.data.putParcelable(null, StartOrStopListeningCommand(listen, statusUpdates))
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