package io.github.tmarsteel.hqrecorder.ui.settings

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.snackbar.Snackbar
import io.github.tmarsteel.hqrecorder.MainActivity
import io.github.tmarsteel.hqrecorder.R
import io.github.tmarsteel.hqrecorder.databinding.FragmentSettingsBinding
import io.github.tmarsteel.hqrecorder.recording.Channel
import io.github.tmarsteel.hqrecorder.recording.ChannelMask
import io.github.tmarsteel.hqrecorder.recording.RecordingConfig
import io.github.tmarsteel.hqrecorder.ui.RecordingConfigViewModel
import io.github.tmarsteel.hqrecorder.ui.StringSpinnerAdapter
import io.github.tmarsteel.hqrecorder.util.items
import io.github.tmarsteel.hqrecorder.util.setSelectedItemByPredicate
import java.text.DecimalFormat

class SettingsFragment : Fragment(), MenuProvider {
    private var _binding: FragmentSettingsBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private var audioManager: AudioManager? = null
    private val selectedDeviceWithMask: AudioDeviceWithChannelMask?
        get() = binding.settingsAudioDeviceSpinner.selectedItem as AudioDeviceWithChannelMask?

    private val recordingConfigViewModel: RecordingConfigViewModel by activityViewModels()
    private lateinit var audioDeviceAdapter: StringSpinnerAdapter<AudioDeviceWithChannelMask>
    private lateinit var samplingRateAdapter: StringSpinnerAdapter<Int>
    private lateinit var sampleEncodingAdapter: StringSpinnerAdapter<SampleEncoding>
    private lateinit var trackConfigAdapter: TrackConfigAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        audioDeviceAdapter = StringSpinnerAdapter(
            context = requireContext(),
            idMapper = { _, d -> d.combinedId },
        )
        samplingRateAdapter = StringSpinnerAdapter(
            context = requireContext(),
            labelGetter = { FREQUENCY_FORMAT.format(it) },
            idMapper = { _, sr -> sr.toLong() },
        )
        sampleEncodingAdapter = StringSpinnerAdapter<SampleEncoding>(
            context = requireContext(),
            labelGetter = SampleEncoding::text,
            idMapper = { _, bd -> bd.encodingValue.toLong() },
        )
        trackConfigAdapter = TrackConfigAdapter(requireContext())

        super.onCreate(savedInstanceState)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.settings_action_bar, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.action_apply_settings -> {
                (requireActivity() as MainActivity).updateRecordingConfig(buildModelFromView())
                return true
            }
        }

        return false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        binding.settingsAudioDeviceSpinner.adapter = audioDeviceAdapter
        binding.settingsSamplingRateSpinner.adapter = samplingRateAdapter
        binding.settingsSampleEncodingSpinner.adapter = sampleEncodingAdapter
        binding.settingsSampleEncodingSpinner.setSelectedItemByPredicate<SampleEncoding> { it  == SampleEncoding.FOUR_BYTES_INTEGER }

        audioManager = requireContext().getSystemService<AudioManager>()
            ?: run {
                Snackbar.make(binding.root, R.string.error_no_audio_manager, Snackbar.LENGTH_LONG).show()
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
                samplingRateAdapter.clear()
                samplingRateAdapter.addAll(deviceWithMask.device.sampleRates.toList().sorted())
                binding.settingsSamplingRateSpinner.setSelectedItemByPredicate<Int> { it == recordingConfigViewModel.config?.samplingRate }

                sampleEncodingAdapter.clear()
                enumValues<SampleEncoding>()
                    .filter { it.encodingValue in deviceWithMask.device.encodings }
                    .sortedBy { it.ordinal }
                    .forEach { sampleEncodingAdapter.add(it) }
                binding.settingsSampleEncodingSpinner.setSelectedItemByPredicate<SampleEncoding> { it.encodingValue == recordingConfigViewModel.config?.encoding }

                trackConfigAdapter.channelMask = deviceWithMask.channelMask
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {

            }
        }

        audioDeviceAdapter.clear()
        audioDeviceCallback.onAudioDevicesAdded(audioManager!!.getDevices(AudioManager.GET_DEVICES_INPUTS))
        audioManager!!.registerAudioDeviceCallback(audioDeviceCallback, null)

        binding.settingsAddMonoTrack.setOnClickListener(this::addMonoTrack)
        binding.settingsAddStereoTrack.setOnClickListener(this::addStereoTrack)
        trackConfigAdapter = TrackConfigAdapter(requireContext())
        binding.settingsTracksList.adapter = trackConfigAdapter

        updateViewWithCurrentViewModel()

        return root
    }

    override fun onStart() {
        super.onStart()

        activity?.addMenuProvider(this)
    }

    override fun onStop() {
        super.onStop()

        activity?.removeMenuProvider(this)
    }

    private fun updateViewWithCurrentViewModel() {
        recordingConfigViewModel.config?.let { currentConfig ->
            binding.settingsAudioDeviceSpinner.setSelectedItemByPredicate<AudioDeviceWithChannelMask> { deviceOption ->
                deviceOption.device.id == currentConfig.deviceId && deviceOption.device.address == currentConfig.deviceAddress
            }
            binding.settingsSamplingRateSpinner.setSelectedItemByPredicate<Int> { it == currentConfig.samplingRate }
            binding.settingsSampleEncodingSpinner.setSelectedItemByPredicate<SampleEncoding> { it.encodingValue == currentConfig.encoding }
            (binding.settingsAudioDeviceSpinner.selectedItem as AudioDeviceWithChannelMask?)?.let { selectedDeviceWithMask ->
                trackConfigAdapter.channelMask = selectedDeviceWithMask.channelMask
            }

            trackConfigAdapter.clear()
            trackConfigAdapter.addAll(currentConfig.tracks)
        }
    }

    private val nextTrackId: Long get()= (trackConfigAdapter.items().maxOfOrNull { it.id } ?: 0L) + 1L
    private val nextTrackFirstChannel: Channel get() {
        val channelsInUse = trackConfigAdapter.items()
            .flatMap {
                sequenceOf(it.leftOrMonoDeviceChannel, it.rightDeviceChannel).filterNotNull()
            }
            .fold(ChannelMask.EMPTY) { mask, channel -> mask + channel }

        val selectedDevice = binding.settingsAudioDeviceSpinner.selectedItem as AudioDeviceWithChannelMask?
        return selectedDevice
            .let { it?.channelMask?.channels ?: Channel.all }
            .filter { it !in channelsInUse }
            .firstOrNull()
            ?: selectedDevice?.channelMask?.channels?.firstOrNull()
            ?: Channel.FIRST
    }

    fun addMonoTrack(button: View?) {
        val id = nextTrackId
        trackConfigAdapter.add(RecordingConfig.InputTrackConfig(
            id,
            "Track $id",
            nextTrackFirstChannel,
            null,
        ))
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
        trackConfigAdapter.add(RecordingConfig.InputTrackConfig(
            id,
            "Track $id",
            leftChannel,
            rightChannel
        ))
    }

    private fun buildModelFromView(): RecordingConfig {
        val selectedDeviceWithMask = binding.settingsAudioDeviceSpinner.selectedItem as AudioDeviceWithChannelMask?
        return RecordingConfig(
            selectedDeviceWithMask?.device?.address ?: RecordingConfig.INITIAL.deviceAddress,
            deviceId = selectedDeviceWithMask?.device?.id ?: RecordingConfig.INITIAL.deviceId,
            channelMask = selectedDeviceWithMask?.channelMask ?: RecordingConfig.INITIAL.channelMask,
            binding.settingsSamplingRateSpinner.selectedItem as Int,
            (binding.settingsSampleEncodingSpinner.selectedItem as SampleEncoding).encodingValue,
            trackConfigAdapter.items().toList(),
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        _binding = null
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            addedDevices.forEach { newDevice ->
                if (!newDevice.isSource) {
                    return
                }
                for (channelMask in ChannelMask.getUniqueMasksFor(newDevice)) {
                    audioDeviceAdapter.add(AudioDeviceWithChannelMask(newDevice, channelMask))
                }
            }
            audioDeviceAdapter.sort(AudioDeviceWithChannelMask::compareTo)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val removedDeviceIds = removedDevices.map { it.id }.toSet()
            (0 until audioDeviceAdapter.count)
                .asSequence()
                .mapNotNull(audioDeviceAdapter::getItem)
                .filter { it.device.id in removedDeviceIds }
                .forEach(audioDeviceAdapter::remove)
        }
    }

    companion object {
        val FREQUENCY_FORMAT = (DecimalFormat.getInstance() as DecimalFormat).also {
            it.applyPattern("##,### Hz")
        }
    }

    enum class SampleEncoding(val text: String, val encodingValue: Int) {
        ONE_BYTE("8", AudioFormat.ENCODING_PCM_8BIT),
        TWO_BYTES("16", AudioFormat.ENCODING_PCM_16BIT),
        THREE_BYTES("24", AudioFormat.ENCODING_PCM_24BIT_PACKED),
        FOUR_BYTES_INTEGER("32", AudioFormat.ENCODING_PCM_32BIT),
        FOUR_BYTES_FLOAT("32 Float", AudioFormat.ENCODING_PCM_FLOAT),
        ;
    }
}