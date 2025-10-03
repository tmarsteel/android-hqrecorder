package io.github.tmarsteel.hqrecorder.ui.recordings

import android.database.Cursor
import io.github.tmarsteel.hqrecorder.util.getInt
import io.github.tmarsteel.hqrecorder.util.getString
import java.nio.file.Paths

data class TrackInfo(
    val filename: String,
    val sampleRate: Int,
    val bitsPerSample: Int,
) {
    companion object {
        val COLUMN_NAME_FILENAME = "_display_name"
        val COLUMN_NAME_BITS_PER_SAMPLE = "bits_per_sample"
        val COLUMN_NAME_SAMPLE_RATE = "samplerate"

        val COLUMN_NAMES = arrayOf(COLUMN_NAME_FILENAME, COLUMN_NAME_BITS_PER_SAMPLE, COLUMN_NAME_SAMPLE_RATE)

        fun fromMediaStoreCursor(cursor: Cursor): TrackInfo {
            return TrackInfo(
                cursor.getString(COLUMN_NAME_FILENAME),
                cursor.getInt(COLUMN_NAME_BITS_PER_SAMPLE),
                cursor.getInt(COLUMN_NAME_SAMPLE_RATE),
            )
        }
    }
}