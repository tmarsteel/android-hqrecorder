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
import io.github.tmarsteel.hqrecorder.R
import io.github.tmarsteel.hqrecorder.databinding.FragmentSettingsBinding
import io.github.tmarsteel.hqrecorder.recording.Channel
import io.github.tmarsteel.hqrecorder.recording.ChannelMask
import io.github.tmarsteel.hqrecorder.recording.RecordingConfig
import io.github.tmarsteel.hqrecorder.ui.StringSpinnerAdapter
import io.github.tmarsteel.hqrecorder.util.humanLabel
import java.text.DecimalFormat

class SettingsFragment : Fragment(), TrackConfigAdapter.TrackConfigChangedListener {

    private var _binding: FragmentSettingsBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private var audioManager: AudioManager? = null

    private var selectedDeviceAddress: String? = null
    private var selectedDeviceId: Int? = null

    private lateinit var trackConfigAdapter: TrackConfigAdapter

    private var recordingConfig = RecordingConfig("", 0, 44100, AudioFormat.ENCODING_PCM_16BIT, emptyList())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
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
        binding.settingsBitDepthSpinner.adapter = StringSpinnerAdapter<BitDepth>(
            context = requireContext(),
            labelGetter = BitDepth::text,
            idMapper = { _, bd -> bd.encodingValue.toLong() },
        ).also {
            it.addAll(*enumValues<BitDepth>())
        }
        binding.settingsBitDepthSpinner.setSelection(enumValues<BitDepth>().indexOf(BitDepth.FOUR_BYTES_INTEGER))

        audioManager = requireContext().getSystemService<AudioManager>()
            ?: run {
                Toast.makeText(requireContext(), R.string.error_no_audio_manager, Toast.LENGTH_LONG).show()
                return root
            }

        binding.settingsAudioDeviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val device = audioManager
                    ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    ?.singleOrNull { it.id.toLong() == id }
                    ?: return onNothingSelected(parent)

                selectedDeviceAddress = device.address
                selectedDeviceId = device.id
                val samplingRatesAdapter = binding.settingsSamplingRateSpinner.adapter as ArrayAdapter<Int>
                samplingRatesAdapter.clear()
                samplingRatesAdapter.addAll(device.sampleRates.toList().sorted())

                val bitDepthsAdapter = binding.settingsBitDepthSpinner.adapter as ArrayAdapter<BitDepth>
                bitDepthsAdapter.clear()
                enumValues<BitDepth>()
                    .filter { it.encodingValue in device.encodings }
                    .sortedBy { it.ordinal }
                    .forEach { bitDepthsAdapter.add(it) }

                trackConfigAdapter.availableChannels = device.channelCounts.maxOrNull()?.toUInt() ?: 128u

                Log.i(SettingsFragment::class.simpleName, "Device ${device.address} selected with ${device.channelCounts.maxOrNull()} channels, sampling rates ${device.sampleRates.contentToString()}, encodings ${device.encodings.contentToString()}")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedDeviceAddress = null
                selectedDeviceId = null
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

        return root
    }

    private val nextTrackId: Long get() = (recordingConfig.tracks.maxOfOrNull { it.id } ?: -1L) + 1

    fun addMonoTrack(button: View?) {
        val id = nextTrackId
        val channel = recordingConfig.tracks.maxOfOrNull { it.leftOrMonoDeviceChannel.coerceAtLeast(it.rightDeviceChannel) } ?: Channel.FIRST
        val track = RecordingConfig.InputTrackConfig(id, "Track ${id + 1}", channel, null)
        recordingConfig.tracks = recordingConfig.tracks + track
        trackConfigAdapter.add(track)
    }

    fun addStereoTrack(button: View?) {
        val id = nextTrackId
        val leftChannel = recordingConfig.tracks.maxOfOrNull { it.leftOrMonoDeviceChannel.coerceAtLeast(it.rightDeviceChannel) } ?: Channel.FIRST
        val rightChannel = leftChannel.next()
        val track = RecordingConfig.InputTrackConfig(id, "Track ${id + 1}", leftChannel, rightChannel)
        recordingConfig.tracks = recordingConfig.tracks + track
        trackConfigAdapter.add(track)
    }

    override fun onTrackConfigChanged(id: Long) {
        // nothing to do, really
    }

    override fun onTrackDeleteRequested(id: Long) {
        val track = recordingConfig.tracks.singleOrNull { it.id == id } ?: return
        recordingConfig.tracks = recordingConfig.tracks - track
        trackConfigAdapter.remove(track)
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