package io.github.tmarsteel.hqrecorder.ui.recordings

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import io.github.tmarsteel.hqrecorder.R

class RecordingsTracksAdapter(context: Context) : ArrayAdapter<TrackInfo>(context, R.layout.recordings_track_item, R.id.recordings_track_item_title) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        val trackInfo = getItem(position) as TrackInfo
        view.findViewById<TextView>(R.id.recordings_track_item_title).text = trackInfo.filename
        view.findViewById<TextView>(R.id.recordings_track_item_metrics).text = context.getString(R.string.recordings_metrics).format(
            trackInfo.sampleRate,
            trackInfo.bitsPerSample,
        )

        return view
    }
}