package io.github.tmarsteel.hqrecorder.ui.settings

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.allViews
import io.github.tmarsteel.hqrecorder.R
import io.github.tmarsteel.hqrecorder.recording.Channel
import io.github.tmarsteel.hqrecorder.recording.ChannelMask
import io.github.tmarsteel.hqrecorder.recording.RecordingConfig
import io.github.tmarsteel.hqrecorder.util.setSelectedItemByPredicate

class TrackConfigAdapter(context: Context) : BaseAdapter() {
    var channelMask: ChannelMask = ChannelMask.EMPTY
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val layoutInflater = LayoutInflater.from(context)
    private val _trackConfigs = ArrayList<RecordingConfig.InputTrackConfig>()
    val trackConfigs: List<RecordingConfig.InputTrackConfig>
        get() = _trackConfigs

    private fun createView(parent: ViewGroup): View {
        return layoutInflater.inflate(R.layout.view_settings_track_config, parent, false)
    }

    override fun getItem(position: Int): RecordingConfig.InputTrackConfig {
        return trackConfigs[position]
    }

    override fun getCount(): Int {
        return trackConfigs.size
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id
    }

    fun removeAt(position: Int) {
        _trackConfigs.removeAt(position)
        notifyDataSetChanged()
    }

    fun clear() {
        _trackConfigs.clear()
        notifyDataSetChanged()
    }

    fun add(config: RecordingConfig.InputTrackConfig) {
        _trackConfigs.add(config)
        notifyDataSetChanged()
    }

    fun addAll(configs: Collection<RecordingConfig.InputTrackConfig>) {
        _trackConfigs.addAll(configs)
        notifyDataSetChanged()
    }

    private fun updateTrackById(id: Long, updater: (RecordingConfig.InputTrackConfig) -> RecordingConfig.InputTrackConfig) {
        val position = _trackConfigs.indexOfFirst { it.id == id }
        if (position == -1) {
            throw NoSuchElementException()
        }
        _trackConfigs[position] = _trackConfigs[position].let(updater)
    }

    override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup
    ): View {
        val view = convertView ?: createView(parent)
        val track = getItem(position)

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
            removeAt(position)
        }

        (convertView?.getTag(R.id.settings_track_holder_tag) as TrackViewHolder?)?.let { previousHolder ->
            labelEdit.removeTextChangedListener(previousHolder.labelWatcher)
        }

        val labelWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                updateTrackById(track.id) { it.copy(label = s.toString()) }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

            }
        }
        labelEdit.addTextChangedListener(labelWatcher)
        leftSourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                updateTrackById(track.id) { it.copy(leftOrMonoDeviceChannel = parent.getItemAtPosition(position) as Channel) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {

            }
        }
        if (track.rightDeviceChannel != null) {
            rightSourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                    updateTrackById(track.id) { it.copy(rightDeviceChannel = parent.getItemAtPosition(position) as Channel) }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {

                }
            }
        }

        view.setTag(R.id.settings_track_holder_tag, TrackViewHolder(track.id, labelWatcher))

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

    private class TrackViewHolder(
        val id: Long,
        val labelWatcher: TextWatcher,
    )
}