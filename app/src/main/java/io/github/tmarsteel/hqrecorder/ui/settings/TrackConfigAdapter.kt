package io.github.tmarsteel.hqrecorder.ui.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import io.github.tmarsteel.hqrecorder.R
import io.github.tmarsteel.hqrecorder.recording.Channel
import io.github.tmarsteel.hqrecorder.recording.ChannelMask
import io.github.tmarsteel.hqrecorder.recording.RecordingConfig
import io.github.tmarsteel.hqrecorder.ui.SignalLevelIndicatorView

class TrackConfigAdapter(context: Context) : ArrayAdapter<RecordingConfig.InputTrackConfig>(context, R.layout.view_settings_track_config, R.id.settings_track_label) {
    var channelMask: ChannelMask = ChannelMask.EMPTY
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var trackConfigChangedListener: TrackConfigChangedListener? = null

    override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup
    ): View {
        val view = super.getView(position, convertView, parent)
        val track = getItem(position) ?: return view

        val labelEdit = view.findViewById<EditText>(R.id.settings_track_label)
        labelEdit.setText(track.label)
        labelEdit.setOnEditorActionListener { innerLabelEdit, _ , _ ->
            trackConfigChangedListener?.let { listener ->
                val newTrack = (getItem(position) as RecordingConfig.InputTrackConfig).copy(
                    label = innerLabelEdit.text.toString()
                )
                listener.onTrackChanged(newTrack)
            }
            false
        }

        val leftSourceSpinner = view.findViewById<Spinner>(R.id.settings_track_left_source_spinner)
        leftSourceSpinner.adapter as? SourceChannelAdapter ?: run {
            val adapter = SourceChannelAdapter(parent.context)
            leftSourceSpinner.adapter = adapter
        }
        leftSourceSpinner.setSelection(channelMask.channels.indexOf(track.leftOrMonoDeviceChannel))
        leftSourceSpinner.onItemSelectedListener = SourceChannelChangeListener(position, false)
        val leftLevel = view.findViewById<SignalLevelIndicatorView>(R.id.settings_track_signal_level_left)
        leftLevel.channelIndicator = if (track.rightDeviceChannel != null) "L" else ""
        leftLevel.indicatesLeftOrRight = false
        leftLevel.indicatesTrackId = track.id

        val rightGroup = view.findViewById<View>(R.id.settings_track_right_source_group)
        val rightSourceSpinner = view.findViewById<Spinner>(R.id.settings_track_right_source_spinner)
        val rightLevel = view.findViewById<SignalLevelIndicatorView>(R.id.settings_track_signal_level_right)
        if (track.rightDeviceChannel == null) {
            rightSourceSpinner.onItemSelectedListener = null
            rightGroup.visibility = View.INVISIBLE
            rightLevel.visibility = View.INVISIBLE
            rightLevel.indicatesTrackId = null
        } else {
            rightGroup.visibility = View.VISIBLE
            rightLevel.visibility = View.VISIBLE
            rightLevel.channelIndicator = "R"
            rightLevel.indicatesTrackId = track.id
            rightLevel.indicatesLeftOrRight = true

            rightSourceSpinner.adapter as? SourceChannelAdapter ?: run {
                val adapter = SourceChannelAdapter(parent.context)
                rightSourceSpinner.adapter = adapter
            }
            rightSourceSpinner.setSelection(channelMask.channels.indexOf(track.rightDeviceChannel))
            rightSourceSpinner.onItemSelectedListener = SourceChannelChangeListener(position, false)
        }

        view.findViewById<Button>(R.id.settings_track_delete_button).setOnClickListener {
            trackConfigChangedListener?.onTrackDeleteRequested(track.id)
        }

        return view
    }

    private inner class SourceChannelAdapter(private val context: Context) : BaseAdapter() {
        private val layoutInflater = LayoutInflater.from(context)

        override fun getCount(): Int {
            return channelMask.count
        }

        override fun getItem(position: Int): Channel? {
            return channelMask.channels.drop(position).firstOrNull()
        }

        override fun getItemId(position: Int): Long {
            if (position < 0) {
                return Long.MIN_VALUE
            }
            
            return channelMask.channels.drop(position).firstOrNull()?.number?.toLong() ?: 0
        }

        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup?
        ): View? {
            val view = convertView as? TextView ?: layoutInflater.inflate(R.layout.view_spinner_text, parent, false) as View
            view.findViewById<TextView>(R.id.util_spinner_label).text = getItem(position).toString()
            return view
        }

        override fun getDropDownView(
            position: Int,
            convertView: View?,
            parent: ViewGroup?
        ): View? {
            return getView(position, convertView, parent)
        }

        override fun hasStableIds(): Boolean {
            return true
        }
    }

    inner class SourceChannelChangeListener(val trackAdapterPosition: Int, val isRight: Boolean) : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
            parent: AdapterView<*>,
            view: View?,
            position: Int,
            id: Long
        ) {
            trackConfigChangedListener?.let { listener ->
                val track = this@TrackConfigAdapter.getItem(trackAdapterPosition) as RecordingConfig.InputTrackConfig
                val newChannel = parent.adapter.getItem(position) as Channel
                val newTrack = if (isRight) {
                    track.copy(rightDeviceChannel = newChannel)
                } else {
                    track.copy(leftOrMonoDeviceChannel = newChannel)
                }
                listener.onTrackChanged(newTrack)
            }
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {

        }
    }

    interface TrackConfigChangedListener {
        fun onTrackChanged(newTrackData: RecordingConfig.InputTrackConfig)
        fun onTrackDeleteRequested(id: Long)
    }

    companion object {
        val LEFT_DEFAULT_CHANNEL = Channel.FIRST
        val RIGHT_DEFAULT_CHANNEL = Channel.SECOND
    }
}