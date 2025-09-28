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
import io.github.tmarsteel.hqrecorder.recording.RecordingConfig
import io.github.tmarsteel.hqrecorder.ui.SignalLevelIndicatorView

class TrackConfigAdapter(context: Context) : ArrayAdapter<RecordingConfig.InputTrackConfig>(context, R.layout.view_settings_track_config, R.id.settings_track_label) {
    var availableChannels: UInt = 1u
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
        val item = getItem(position) ?: return view

        val labelEdit = view.findViewById<EditText>(R.id.settings_track_label)
        labelEdit.setText(item.label)
        labelEdit.setOnEditorActionListener { innerLabelEdit, _ , _ ->
            item.label = innerLabelEdit.text.toString()
            false
        }

        val leftSourceSpinner = view.findViewById<Spinner>(R.id.settings_track_left_source_spinner)
        val leftSourceSpinnerAdapter = leftSourceSpinner.adapter as? SourceChannelAdapter ?: run {
            val adapter = SourceChannelAdapter(parent.context)
            leftSourceSpinner.adapter = adapter
            adapter
        }
        leftSourceSpinnerAdapter.availableChannels = availableChannels
        leftSourceSpinner.setSelection((item.leftOrMonoDeviceChannel.number - 1).toInt())
        leftSourceSpinner.onItemSelectedListener = SourceChannelChangeListener(item.id, LEFT_DEFAULT_CHANNEL, item::leftOrMonoDeviceChannel::set)
        val leftLevel = view.findViewById<SignalLevelIndicatorView>(R.id.settings_track_signal_level_left)
        leftLevel.channelIndicator = if (item.rightDeviceChannel != null) "L" else ""

        val rightGroup = view.findViewById<View>(R.id.settings_track_right_source_group)
        val rightSourceSpinner = view.findViewById<Spinner>(R.id.settings_track_right_source_spinner)
        val rightLevel = view.findViewById<SignalLevelIndicatorView>(R.id.settings_track_signal_level_right)
        if (item.rightDeviceChannel == null) {
            rightSourceSpinner.onItemSelectedListener = null
            rightGroup.visibility = View.INVISIBLE
            rightLevel.visibility = View.INVISIBLE
        } else {
            rightGroup.visibility = View.VISIBLE
            rightLevel.visibility = View.VISIBLE
            rightLevel.channelIndicator = "R"

            val rightSourceSpinnerAdapter = rightSourceSpinner.adapter as? SourceChannelAdapter ?: run {
                val adapter = SourceChannelAdapter(parent.context)
                rightSourceSpinner.adapter = adapter
                adapter
            }
            rightSourceSpinnerAdapter.availableChannels = availableChannels
            rightSourceSpinner.setSelection(((item.rightDeviceChannel ?: Channel.SECOND).number - 1))
            rightSourceSpinner.onItemSelectedListener = SourceChannelChangeListener(item.id, RIGHT_DEFAULT_CHANNEL, item::leftOrMonoDeviceChannel::set)
        }

        view.findViewById<Button>(R.id.settings_track_delete_button).setOnClickListener {
            trackConfigChangedListener?.onTrackDeleteRequested(item.id)
        }

        return view
    }

    class SourceChannelAdapter(private val context: Context) : BaseAdapter() {
        private val layoutInflater = LayoutInflater.from(context)

        var availableChannels: UInt = 1u
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getCount(): Int {
            return availableChannels.toInt()
        }

        override fun getItem(position: Int): Any? {
            return position.takeIf { it.toUInt() < availableChannels }
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup?
        ): View? {
            val view = convertView as? TextView ?: layoutInflater.inflate(R.layout.view_spinner_text, parent, false) as View
            view.findViewById<TextView>(R.id.util_spinner_label).text = (position + 1).toString()
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

    inner class SourceChannelChangeListener(val trackId: Long, val defaultChannel: Channel, val updateTarget: (Channel)  -> Unit) : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
            parent: AdapterView<*>?,
            view: View?,
            position: Int,
            id: Long
        ) {
            updateTarget(Channel(position + 1))
            trackConfigChangedListener?.onTrackConfigChanged(trackId)
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {
            updateTarget(defaultChannel)
            trackConfigChangedListener?.onTrackConfigChanged(trackId)
        }
    }

    interface TrackConfigChangedListener {
        fun onTrackConfigChanged(id: Long)
        fun onTrackDeleteRequested(id: Long)
    }

    companion object {
        val LEFT_DEFAULT_CHANNEL = Channel.FIRST
        val RIGHT_DEFAULT_CHANNEL = Channel.SECOND
    }
}