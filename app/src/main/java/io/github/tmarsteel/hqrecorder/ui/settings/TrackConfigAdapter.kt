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
import io.github.tmarsteel.hqrecorder.util.setSelectedItemByPredicate

class TrackConfigAdapter(context: Context) : ArrayAdapter<RecordingConfig.InputTrackConfig>(context, R.layout.view_settings_track_config, R.id.settings_track_label) {
    var channelMask: ChannelMask = ChannelMask.EMPTY
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup
    ): View {
        val view = super.getView(position, convertView, parent)
        val track = getItem(position) ?: return view

        val labelEdit = view.findViewById<EditText>(R.id.settings_track_label)
        labelEdit.setText(track.label)

        val leftSourceSpinner = view.findViewById<Spinner>(R.id.settings_track_left_source_spinner)
        if (leftSourceSpinner.adapter !is SourceChannelAdapter) {
            leftSourceSpinner.adapter = SourceChannelAdapter(parent.context)
        }
        leftSourceSpinner.setSelectedItemByPredicate<Channel> { it == track.leftOrMonoDeviceChannel }

        val rightGroup = view.findViewById<View>(R.id.settings_track_right_source_group)
        val rightSourceSpinner = view.findViewById<Spinner>(R.id.settings_track_right_source_spinner)
        if (track.rightDeviceChannel == null) {
            rightSourceSpinner.onItemSelectedListener = null
            rightGroup.visibility = View.INVISIBLE
        } else {
            rightGroup.visibility = View.VISIBLE

            if (rightSourceSpinner.adapter !is SourceChannelAdapter) {
                rightSourceSpinner.adapter = SourceChannelAdapter(parent.context)
            }
            rightSourceSpinner.setSelectedItemByPredicate<Channel> { it == track.rightDeviceChannel }
        }

        view.findViewById<Button>(R.id.settings_track_delete_button).setOnClickListener {
            remove(getItem(position))
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
            
            return getItem(position)?.number?.toLong() ?: 0L
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
}