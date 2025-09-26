package io.github.tmarsteel.hqrecorder.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import io.github.tmarsteel.hqrecorder.R

class StringSpinnerAdapter<T : Any>(
    context: Context,
    val labelGetter: (T) -> CharSequence = Any::toString,
    val idMapper: (Int, T) -> Long = { position, item -> position.toLong() },
) : ArrayAdapter<T>(context, R.layout.view_spinner_text, R.id.util_spinner_label) {
    override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup
    ): View {
        val view = super.getView(position, convertView, parent)
        val textView = view.findViewById<TextView>(R.id.util_spinner_label)
        textView.text = getItem(position)?.let(labelGetter) ?: "???"
        return view
    }

    override fun getDropDownView(
        position: Int,
        convertView: View?,
        parent: ViewGroup
    ): View {
        return getView(position, convertView, parent)
    }

    override fun getItemId(position: Int): Long {
        val item = getItem(position) ?: return 0L
        return idMapper(position, item)
    }
}