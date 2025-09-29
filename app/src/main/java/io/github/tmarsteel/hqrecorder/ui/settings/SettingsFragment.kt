package io.github.tmarsteel.hqrecorder.ui.settings

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import io.github.tmarsteel.hqrecorder.MainActivity
import io.github.tmarsteel.hqrecorder.R
import io.github.tmarsteel.hqrecorder.databinding.FragmentSettingsBinding
import io.github.tmarsteel.hqrecorder.recording.Channel
import io.github.tmarsteel.hqrecorder.recording.ChannelMask
import io.github.tmarsteel.hqrecorder.recording.RecordingConfig
import io.github.tmarsteel.hqrecorder.recording.RecordingStatusServiceMessage
import io.github.tmarsteel.hqrecorder.ui.ListeningStatusSubscriber
import io.github.tmarsteel.hqrecorder.ui.RecordingConfigViewModel
import io.github.tmarsteel.hqrecorder.ui.SignalLevelIndicatorView
import io.github.tmarsteel.hqrecorder.ui.StringSpinnerAdapter
import io.github.tmarsteel.hqrecorder.util.allViewsInTree
import java.text.DecimalFormat

class SettingsFragment : Fragment(),
    TrackConfigAdapter.TrackConfigChangedListener,
        ListeningStatusSubscriber
{
    private var _binding: FragmentSettingsBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private var audioManager: AudioManager? = null
    private val selectedDeviceWithMask: AudioDeviceWithChannelMask?
        get() = binding.settingsAudioDeviceSpinner.selectedItem as AudioDeviceWithChannelMask?
    private lateinit var trackConfigAdapter: TrackConfigAdapter

    private val recordingConfigViewModel: RecordingConfigViewModel by activityViewModels()

    private var recordingConfig = RecordingConfig(
        "",
        0,
        ChannelMask.EMPTY,
        44100,
        AudioFormat.ENCODING_PCM_16BIT,
        emptyList(),
    )

    private var viewCreated = false
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewCreated = false
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        binding.settingsAudioDeviceSpinner.adapter = StringSpinnerAdapter<AudioDeviceWithChannelMask>(
            context = requireContext(),
            idMapper = { _, d -> d.combinedId },
        )
        binding.settingsSamplingRateSpinner.adapter = StringSpinnerAdapter<Int>(
            context = requireContext(),
            labelGetter = { FREQUENCY_FORMAT.format(it) },
            idMapper = { _, sr -> sr.toLong() },
        ).also {
            it.add(44100)
            it.add(48000)
        }
        binding.settingsSamplingRateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateConfigInViewModel()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                updateConfigInViewModel()
            }
        }
        binding.settingsBitDepthSpinner.adapter = StringSpinnerAdapter<BitDepth>(
            context = requireContext(),
            labelGetter = BitDepth::text,
            idMapper = { _, bd -> bd.encodingValue.toLong() },
        ).also {
            it.addAll(*enumValues<BitDepth>())
        }
        binding.settingsSamplingRateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateConfigInViewModel()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                updateConfigInViewModel()
            }
        }
        binding.settingsBitDepthSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateConfigInViewModel()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                updateConfigInViewModel()
            }
        }
        binding.settingsBitDepthSpinner.setSelection(enumValues<BitDepth>().indexOf(BitDepth.FOUR_BYTES_INTEGER))

        audioManager = requireContext().getSystemService<AudioManager>()
            ?: run {
                Toast.makeText(requireContext(), R.string.error_no_audio_manager, Toast.LENGTH_LONG).show()
                return root
            }

        binding.settingsAudioDeviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                val deviceWithMask = parent.adapter.getItem(position) as AudioDeviceWithChannelMask
                val samplingRatesAdapter = binding.settingsSamplingRateSpinner.adapter as ArrayAdapter<Int>
                samplingRatesAdapter.clear()
                samplingRatesAdapter.addAll(deviceWithMask.device.sampleRates.toList().sorted())

                val bitDepthsAdapter = binding.settingsBitDepthSpinner.adapter as ArrayAdapter<BitDepth>
                bitDepthsAdapter.clear()
                enumValues<BitDepth>()
                    .filter { it.encodingValue in deviceWithMask.device.encodings }
                    .sortedBy { it.ordinal }
                    .forEach { bitDepthsAdapter.add(it) }

                recordingConfig.deviceId = deviceWithMask.device.id
                recordingConfig.deviceAddress = deviceWithMask.device.address
                recordingConfig.channelMask = deviceWithMask.channelMask
                trackConfigAdapter.channelMask = deviceWithMask.channelMask
                updateConfigInViewModel()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {

            }
        }

        audioDeviceCallback.onAudioDevicesAdded(audioManager!!.getDevices(AudioManager.GET_DEVICES_INPUTS))
        audioManager!!.registerAudioDeviceCallback(audioDeviceCallback, null)

        binding.settingsAddMonoTrack.setOnClickListener(this::addMonoTrack)
        binding.settingsAddStereoTrack.setOnClickListener(this::addStereoTrack)
        trackConfigAdapter = TrackConfigAdapter(requireContext())
        binding.settingsTracksList.adapter = trackConfigAdapter
        trackConfigAdapter.clear()
        trackConfigAdapter.addAll(recordingConfig.tracks)
        trackConfigAdapter.trackConfigChangedListener = this

        viewCreated = true

        return root
    }

    private val nextTrackId: Long get() = (recordingConfig.tracks.maxOfOrNull { it.id } ?: -1L) + 1

    fun addMonoTrack(button: View?) {
        val id = nextTrackId
        val channel = (selectedDeviceWithMask?.channelMask?.channels ?: Channel.all)
            .filter { it !in recordingConfig.tracks.flatMap { listOfNotNull(it.leftOrMonoDeviceChannel, it.rightDeviceChannel) } }
            .firstOrNull()
            ?: selectedDeviceWithMask?.channelMask?.channels?.firstOrNull()
            ?: Channel.FIRST
        val track = RecordingConfig.InputTrackConfig(id, "Track ${id + 1}", channel, null)
        recordingConfig.tracks = recordingConfig.tracks + track
        trackConfigAdapter.add(track)
        updateConfigInViewModel()
    }

    fun addStereoTrack(button: View?) {
        val id = nextTrackId
        val leftChannel = (selectedDeviceWithMask?.channelMask?.channels ?: Channel.all)
            .filter { it !in recordingConfig.tracks.flatMap { listOfNotNull(it.leftOrMonoDeviceChannel, it.rightDeviceChannel) } }
            .firstOrNull()
            ?: selectedDeviceWithMask?.channelMask?.channels?.firstOrNull()
            ?: Channel.FIRST
        val potentialRightChannel = try {
            Channel(leftChannel.number + 1)
        } catch (_: IllegalArgumentException) {
            leftChannel
        }
        val rightChannel = potentialRightChannel.takeIf { it in (selectedDeviceWithMask?.channelMask?.channels ?: Channel.all) } ?: leftChannel
        val track = RecordingConfig.InputTrackConfig(id, "Track ${id + 1}", leftChannel, rightChannel)
        recordingConfig.tracks = recordingConfig.tracks + track
        trackConfigAdapter.add(track)
        updateConfigInViewModel()
    }

    override fun onTrackConfigChanged(id: Long) {
        updateConfigInViewModel()
    }

    override fun onTrackDeleteRequested(id: Long) {
        val track = recordingConfig.tracks.singleOrNull { it.id == id } ?: return
        recordingConfig.tracks = recordingConfig.tracks - track
        trackConfigAdapter.remove(track)
        updateConfigInViewModel()
    }

    private fun updateConfigInViewModel() {
        if (!viewCreated) {
            return
        }
        recordingConfigViewModel.config.value = recordingConfig.copy()
        if (recordingConfig.tracks.any()) {
            (requireActivity() as MainActivity).registerListeningSubscriber(this)
        } else {
            (requireActivity() as MainActivity).unregisterListeningSubscriber(this)
        }
    }

    override fun onListeningStarted() {
        view?.allViewsInTree
            ?.filterIsInstance<SignalLevelIndicatorView>()
            ?.forEach { it.reset() }
    }

    override fun onStatusUpdate(update: RecordingStatusServiceMessage) {
        view?.allViewsInTree
            ?.filterIsInstance<SignalLevelIndicatorView>()
            ?.filter { it.indicatesTrackId != null }
            ?.forEach { indicator ->
                update.trackLevels[indicator.indicatesTrackId!!]?.let { trackLevels ->
                    val level = if (indicator.indicatesLeftOrRight) (trackLevels.second ?: 0.0f) else trackLevels.first
                    indicator.update(level)
                }
            }
    }

    override fun onListeningStopped() {
        // TODO: reset all the level indicators, RETAINING THE TRUE PEAK AND CLIP FLAG!!!
    }

    override fun onDestroyView() {
        super.onDestroyView()
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        _binding = null
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val binding = _binding ?: return
            val adapter = binding.settingsAudioDeviceSpinner.adapter as ArrayAdapter<AudioDeviceWithChannelMask>
            addedDevices.forEach { newDevice ->
                if (!newDevice.isSource) {
                    return
                }
                for (channelMask in ChannelMask.getUniqueMasksFor(newDevice)) {
                    adapter.add(AudioDeviceWithChannelMask(newDevice, channelMask))
                }
            }
            adapter.sort(AudioDeviceWithChannelMask::compareTo)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val binding = _binding ?: return
            val adapter = (binding.settingsAudioDeviceSpinner.adapter as ArrayAdapter<AudioDeviceWithChannelMask>)
            val removedDeviceIds = removedDevices.map { it.id }.toSet()
            (0 until adapter.count)
                .asSequence()
                .mapNotNull(adapter::getItem)
                .filter { it.device.id in removedDeviceIds }
                .forEach(adapter::remove)
        }
    }

    companion object {
        val FREQUENCY_FORMAT = (DecimalFormat.getInstance() as DecimalFormat).also {
            it.applyPattern("##,### Hz")
        }
    }

    enum class BitDepth(val text: String, val encodingValue: Int) {
        ONE_BYTE("8", AudioFormat.ENCODING_PCM_8BIT),
        TWO_BYTES("16", AudioFormat.ENCODING_PCM_16BIT),
        THREE_BYTES("24", AudioFormat.ENCODING_PCM_24BIT_PACKED),
        FOUR_BYTES_INTEGER("32", AudioFormat.ENCODING_PCM_32BIT),
        FOUR_BYTES_FLOAT("32 Float", AudioFormat.ENCODING_PCM_FLOAT),
        ;
    }
}