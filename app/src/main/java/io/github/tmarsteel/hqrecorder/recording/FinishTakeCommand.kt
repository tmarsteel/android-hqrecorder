package io.github.tmarsteel.hqrecorder.recording

import android.os.Bundle
import android.os.Message
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
class FinishTakeCommand : Parcelable {
    companion object {
        const val WHAT_VALUE = 4

        fun buildMessage(): Message {
            val message = Message.obtain(null, WHAT_VALUE)
            message.data = Bundle()
            message.data.putParcelable(null, FinishTakeCommand())
            return message
        }

        fun fromMessage(message: Message): FinishTakeCommand? {
            if (message.what != WHAT_VALUE) {
                return null
            }
            return message.data.getParcelable(null, FinishTakeCommand::class.java)
        }
    }

    @Parcelize
    data class Response(val result: Result) : Parcelable {
        enum class Result {
            FINISHED,
            NOT_RECORDING,
            INVALID_STATE,
            ;
        }

        companion object {
            const val WHAT_VALUE = 9

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