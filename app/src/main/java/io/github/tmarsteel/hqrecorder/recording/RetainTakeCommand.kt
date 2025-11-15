package io.github.tmarsteel.hqrecorder.recording

import android.os.Bundle
import android.os.Message
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * For a take that was finished with [FinishTakeCommand.retainTake] being `false`,
 * retroactively makes the take visible to the user / switches to `true`.
 */
@Parcelize
data class RetainTakeCommand(
    /**
     * see [FinishTakeCommand.Response.moveToMediaStoreId]
     */
    val id: Int,
) : Parcelable {
    companion object {
        const val WHAT_VALUE = 10

        fun buildMessage(id: Int): Message {
            val message = Message.obtain(null, WHAT_VALUE)
            message.data = Bundle()
            message.data.putParcelable(null, RetainTakeCommand(id))
            return message
        }

        fun fromMessage(message: Message): RetainTakeCommand? {
            if (message.what != WHAT_VALUE) {
                return null
            }
            return message.data.getParcelable(null, RetainTakeCommand::class.java)
        }
    }

    @Parcelize
    data class Response(val id: Int, val success: Boolean) : Parcelable {
        companion object {
            const val WHAT_VALUE = 11

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