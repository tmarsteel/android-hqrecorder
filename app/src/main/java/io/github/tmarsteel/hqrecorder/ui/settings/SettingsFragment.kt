package io.github.tmarsteel.hqrecorder.ui.settings

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Bundle
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
import io.github.tmarsteel.hqrecorder.util.setSelectedItemByPredicate
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
    private fun updateRecordingConfig(
        deviceId: Int = (recordingConfigViewModel.config.value ?: RecordingConfig.INITIAL).deviceId,
        deviceAddress: String = (recordingConfigViewModel.config.value ?: RecordingConfig.INITIAL).deviceAddress,
        samplingRate: Int = (recordingConfigViewModel.config.value ?: RecordingConfig.INITIAL).samplingRate,
        encoding: Int = (recordingConfigViewModel.config.value ?: RecordingConfig.INITIAL).encoding,
        channelMask: ChannelMask = (recordingConfigViewModel.config.value ?: RecordingConfig.INITIAL).channelMask,
    ) {
        if (creatingView) {
            return
        }

        val tracks = recordingConfigViewModel.config.value?.tracks ?: emptyList()
        recordingConfigViewModel.config.postValue(RecordingConfig(
            deviceAddress,
            deviceId,
            channelMask,
            samplingRate,
            encoding,
            tracks,
        ))
    }
    private fun addTrackToViewModel(track: RecordingConfig.InputTrackConfig) {
        if (creatingView) {
            return
        }

        val old = recordingConfigViewModel.config.value ?: RecordingConfig.INITIAL
        recordingConfigViewModel.config.postValue(old.copy(
            tracks = old.tracks + track
        ))
    }
    private fun updateTracksInViewModel(mapper: (RecordingConfig.InputTrackConfig) -> RecordingConfig.InputTrackConfig?) {
        if (creatingView) {
            return
        }

        val old = recordingConfigViewModel.config.value ?: RecordingConfig.INITIAL
        val newTracks = old.tracks.toMutableList()
        val newTracksIt = newTracks.listIterator()
        while (newTracksIt.hasNext()) {
            val newTrack = newTracksIt.next().let(mapper)
            if (newTrack == null) {
                newTracksIt.remove()
            } else {
                newTracksIt.set(newTrack)
            }
        }
        recordingConfigViewModel.config.postValue(old.copy(
            tracks = newTracks
        ))
    }

    private var creatingView = false
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        creatingView = true
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
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                updateRecordingConfig(samplingRate = parent.adapter.getItem(position) as Int)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                updateRecordingConfig(samplingRate = 0)
            }
        }
        binding.settingsBitDepthSpinner.adapter = StringSpinnerAdapter<BitDepth>(
            context = requireContext(),
            labelGetter = BitDepth::text,
            idMapper = { _, bd -> bd.encodingValue.toLong() },
        ).also {
            it.addAll(*enumValues<BitDepth>())
        }
        binding.settingsBitDepthSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                updateRecordingConfig(encoding = (parent.adapter.getItem(position) as BitDepth).encodingValue)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                updateRecordingConfig(encoding = AudioFormat.ENCODING_PCM_16BIT)
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

                trackConfigAdapter.channelMask = deviceWithMask.channelMask
                updateRecordingConfig(
                    deviceId = deviceWithMask.device.id,
                    deviceAddress = deviceWithMask.device.address,
                    channelMask = deviceWithMask.channelMask,
                )
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
        trackConfigAdapter.trackConfigChangedListener = this

        creatingView = false

        recordingConfigViewModel.config.observe(viewLifecycleOwner) { newConfig ->
            binding.settingsAudioDeviceSpinner.setSelectedItemByPredicate<AudioDeviceWithChannelMask> { deviceOption ->
                deviceOption.device.id == newConfig.deviceId && deviceOption.device.address == newConfig.deviceAddress
            }
            val selectedDevice = binding.settingsAudioDeviceSpinner.selectedItem as AudioDeviceWithChannelMask?
            binding.settingsSamplingRateSpinner.setSelectedItemByPredicate<Int> { it == newConfig.samplingRate }
            binding.settingsBitDepthSpinner.setSelectedItemByPredicate<BitDepth> { it.encodingValue == newConfig.encoding }
            (binding.settingsTracksList.adapter as ArrayAdapter<RecordingConfig.InputTrackConfig>).also {
                it.clear()
                it.addAll(newConfig.tracks)
            }
            nextTrackId = newConfig.tracks.maxOfOrNull { it.id } ?: 0
            nextTrackFirstChannel = (selectedDevice?.channelMask?.channels ?: Channel.all)
                .filter { it !in newConfig.tracks.flatMap { listOfNotNull(it.leftOrMonoDeviceChannel, it.rightDeviceChannel) } }
                .firstOrNull()
                ?: selectedDevice?.channelMask?.channels?.firstOrNull()
                ?: Channel.FIRST

            if (newConfig.tracks.any()) {
                (requireActivity() as MainActivity).registerListeningSubscriber(this)
            } else {
                (requireActivity() as MainActivity).unregisterListeningSubscriber(this)
            }
        }

        return root
    }

    private var nextTrackId: Long = 0
    private var nextTrackFirstChannel: Channel = Channel.FIRST

    fun addMonoTrack(button: View?) {
        val id = nextTrackId
        val track = RecordingConfig.InputTrackConfig(id, "Track ${id + 1}", nextTrackFirstChannel, null)
        addTrackToViewModel(track)
    }

    fun addStereoTrack(button: View?) {
        val id = nextTrackId
        val leftChannel = nextTrackFirstChannel
        val potentialRightChannel = try {
            Channel(leftChannel.number + 1)
        } catch (_: IllegalArgumentException) {
            leftChannel
        }
        val rightChannel = potentialRightChannel.takeIf { it in (selectedDeviceWithMask?.channelMask?.channels ?: Channel.all) } ?: leftChannel
        val track = RecordingConfig.InputTrackConfig(id, "Track ${id + 1}", leftChannel, rightChannel)
        addTrackToViewModel(track)
    }

    override fun onTrackChanged(newTrackData: RecordingConfig.InputTrackConfig) {
        updateTracksInViewModel { oldTrackData ->
            if (oldTrackData.id == newTrackData.id) newTrackData else oldTrackData
        }
    }

    override fun onTrackDeleteRequested(id: Long) {
        updateTracksInViewModel {
            if (it.id == id) null else it
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
        (requireActivity() as? MainActivity)?.unregisterListeningSubscriber(this)
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