package io.github.tmarsteel.hqrecorder.util

import android.widget.AdapterView
import android.widget.ArrayAdapter

fun <T> AdapterView<*>.setSelectedItemByPredicate(predicate: (T) -> Boolean) {
    for (optionIdx in 0 until count) {
        val item = getItemAtPosition(optionIdx) as T
        if (predicate(item)) {
            setSelection(optionIdx)
            return
        }
    }
}

fun <T> ArrayAdapter<T>.items(): Sequence<T> = sequence {
    for (position in 0 until count) {
        @Suppress("UNCHECKED_CAST")
        yield(getItem(position) as T)
    }
}