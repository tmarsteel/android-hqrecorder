package io.github.tmarsteel.hqrecorder.util

import android.database.Cursor
import androidx.annotation.IntRange

@IntRange(from=0)
fun Cursor.forceGetColumnIndex(columnName: String): Int {
    val columnIdx = getColumnIndex(columnName)
    if (columnIdx < 0) {
        throw RuntimeException("The cursor doesn't have column $columnName")
    }

    return columnIdx
}

fun Cursor.getString(columnName: String): String {
    return getString(forceGetColumnIndex(columnName))
}

fun Cursor.getInt(columnName: String): Int {
    return getInt(forceGetColumnIndex(columnName))
}