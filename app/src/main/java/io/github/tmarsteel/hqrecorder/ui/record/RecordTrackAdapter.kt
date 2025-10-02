package io.github.tmarsteel.hqrecorder.ui.record

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import io.github.tmarsteel.hqrecorder.R
import io.github.tmarsteel.hqrecorder.recording.RecordingConfig
import io.github.tmarsteel.hqrecorder.ui.SignalLevelIndicatorView

class RecordTrackAdapter(context: Context) : ArrayAdapter<RecordingConfig.InputTrackConfig>(context, R.layout.record_track_indicator, R.id.record_track_title) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        val track = getItem(position)!!
        view.findViewById<TextView>(R.id.record_track_title).text = track.label

        val leftLevel = view.findViewById<SignalLevelIndicatorView>(R.id.record_track_indicator_left_or_mono)
        leftLevel.indicatesTrackId = track.id
        leftLevel.indicatesLeftOrRight = false
        leftLevel.channelIndicator = if (track.rightDeviceChannel == null) "" else "L"

        val rightLevel = view.findViewById<SignalLevelIndicatorView>(R.id.record_track_indicator_right)
        rightLevel.indicatesTrackId = track.id
        rightLevel.channelIndicator = "R"
        rightLevel.indicatesLeftOrRight = true

        if (track.rightDeviceChannel != null) {
            rightLevel.visibility = View.VISIBLE
            rightLevel.indicatesTrackId = null
        } else{
            rightLevel.visibility = View.INVISIBLE
        }

        return view
    }
}