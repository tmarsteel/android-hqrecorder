package io.github.tmarsteel.hqrecorder.recording

import android.os.Bundle
import android.os.Message
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Finalizes the current take, storing all data to disk. The data will be deleted soon; retain the data using [RetainTakeCommand]
 * with [Response.moveToMediaStoreId]
 */
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
    data class TakeResult(
        val wasRecording: Boolean,

        /**
         * Use with [RetainTakeCommand]; is not null iff [wasRecording] is `true`.
         */
        val moveToMediaStoreId: Int?,
    ) : Parcelable

    @Parcelize
    data class Response(
        val result: Result,
        val takeResult: TakeResult
    ) : Parcelable {
        enum class Result {
            TAKE_FINISHED,
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

            fun fromMessage(message: Message): Response {
                require(message.what == WHAT_VALUE) {
                    "Expected what=$WHAT_VALUE, got ${message.what}"
                }

                return message.data.getParcelable(null, Response::class.java)!!
            }

            val INVALID_STATE: Response
                get() = Response(
                    result = FinishTakeCommand.Response.Result.INVALID_STATE,
                    takeResult = FinishTakeCommand.TakeResult(wasRecording = false, moveToMediaStoreId = null),
                )
        }
    }
}