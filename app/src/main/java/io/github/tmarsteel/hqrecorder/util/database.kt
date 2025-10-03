package io.github.tmarsteel.hqrecorder.util

import android.database.Cursor
import androidx.annotation.IntRange

fun Cursor.getString(columnName: String): String {
    return getString(getColumnIndexOrThrow(columnName))
}

fun Cursor.getInt(columnName: String): Int {
    return getInt(getColumnIndexOrThrow(columnName))
}