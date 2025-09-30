package io.github.tmarsteel.hqrecorder.util

import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView

val View.allViewsInTree: Sequence<View> get()= sequence {
    yieldAllViewsInTree(this@allViewsInTree)
}

private suspend fun SequenceScope<View>.yieldAllViewsInTree(root: View) {
    yield(root)
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            yieldAllViewsInTree(root.getChildAt(i))
        }
    }
}

fun <T> AdapterView<*>.setSelectedItemByPredicate(predicate: (T) -> Boolean) {
    for (optionIdx in 0 until count) {
        val item = getItemAtPosition(optionIdx) as T
        if (predicate(item)) {
            setSelection(optionIdx)
            return
        }
    }
}