package io.github.tmarsteel.hqrecorder.recording

import android.os.Bundle
import android.os.Message
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
class StartNewTakeCommand : Parcelable {
    companion object {
        /** @see Message.what */
        const val WHAT_VALUE = 1

        fun buildMessage(): Message {
            val message = Message.obtain(null, WHAT_VALUE)
            message.data = Bundle()
            message.data.putParcelable(null, StartNewTakeCommand())
            return message
        }

        fun fromMessage(message: Message): StartNewTakeCommand? {
            if (message.what != WHAT_VALUE) {
                return null
            }
            return message.data.getParcelable(null, StartNewTakeCommand::class.java)
        }
    }

    @Parcelize
    data class Response(val result: Result) : Parcelable {
        enum class Result {
            /**
             * The next take is being recorded
             */
            RECORDING,

            /**
             * The service must be listening. Send [StartOrStopListeningCommand] first.
             */
            INVALID_STATE,

            ;
        }

        companion object {
            const val WHAT_VALUE = 3

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