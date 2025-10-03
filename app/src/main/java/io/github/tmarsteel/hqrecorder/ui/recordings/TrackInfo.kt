package io.github.tmarsteel.hqrecorder.ui.recordings

import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import io.github.tmarsteel.hqrecorder.util.getInt
import io.github.tmarsteel.hqrecorder.util.getString

data class TrackInfo(
    val contentUri: Uri,
    val mimeType: String,
    val filename: String,
    val sampleRate: Int,
    val bitsPerSample: Int,
) {
    companion object {
        val COLUMN_NAME_FILENAME = "_display_name"
        val COLUMN_NAME_BITS_PER_SAMPLE = "bits_per_sample"
        val COLUMN_NAME_SAMPLE_RATE = "samplerate"
        val COLUMN_NAME_ID = "_id"
        val COLUMN_NAME_MIME_TYPE = "mime_type"

        val COLUMN_NAMES = arrayOf(COLUMN_NAME_FILENAME, COLUMN_NAME_BITS_PER_SAMPLE, COLUMN_NAME_SAMPLE_RATE, COLUMN_NAME_ID, COLUMN_NAME_MIME_TYPE)

        fun fromMediaStoreCursor(cursor: Cursor, queriedFrom: Uri): TrackInfo {
            return TrackInfo(
                queriedFrom.buildUpon()
                    .appendPath(cursor.getString(COLUMN_NAME_ID))
                    .build(),
                cursor.getString(COLUMN_NAME_MIME_TYPE),
                cursor.getString(COLUMN_NAME_FILENAME),
                cursor.getInt(COLUMN_NAME_BITS_PER_SAMPLE),
                cursor.getInt(COLUMN_NAME_SAMPLE_RATE),
            )
        }
    }
}