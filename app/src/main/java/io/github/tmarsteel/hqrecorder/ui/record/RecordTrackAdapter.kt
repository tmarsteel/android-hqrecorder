package io.github.tmarsteel.hqrecorder.ui.record

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import io.github.tmarsteel.hqrecorder.R
import io.github.tmarsteel.hqrecorder.recording.RecordingConfig
import io.github.tmarsteel.hqrecorder.ui.SignalLevelIndicator

class RecordTrackAdapter(context: Context) : ArrayAdapter<RecordingConfig.InputTrackConfig>(context, R.layout.record_track_indicator, R.id.record_track_title) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        val track = getItem(position)!!
        view.findViewById<TextView>(R.id.record_track_title).text = track.label

        val indicator = view.findViewById<SignalLevelIndicator>(R.id.record_track_indicator)
        indicator.indicatesTrackId = track.id
        if (track.rightDeviceChannel == null) {
            indicator.setChannelNames(arrayOf(""))
        } else {
            indicator.setChannelNames(arrayOf("L", "R"))
        }

        return view
    }
}