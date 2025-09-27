package io.github.tmarsteel.hqrecorder.recording

import android.os.Bundle
import android.os.Message
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class UpdateRecordingConfigCommand(
    val config: RecordingConfig,
) : Parcelable {
    @Parcelize
    data class Response(val result: Result) : Parcelable {
        enum class Result {
            /** the service is using the new configuration */
            OK,

            /** the service is not using the new configuration; e.g. the requested device isn't found or it doesn't support the requested sampling rate */
            INVALID,

            /** the service is not using the new configuration because it is currently recording. */
            STOP_RECORDING_FIRST,
            ;
        }

        companion object {
            const val WHAT_VALUE = 2

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

    companion object {
        /** @see Message.what */
        const val WHAT_VALUE = 0

        fun buildMessage(config: RecordingConfig): Message {
            val message = Message.obtain(null, WHAT_VALUE)
            message.data = Bundle()
            message.data.putParcelable(null, UpdateRecordingConfigCommand(config))
            return message
        }

        fun fromMessage(message: Message): UpdateRecordingConfigCommand? {
            if (message.what != WHAT_VALUE) {
                return null
            }
            return message.data.getParcelable(null, UpdateRecordingConfigCommand::class.java)
        }
    }
}