package io.github.tmarsteel.hqrecorder.util

import android.view.View
import android.view.ViewGroup

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