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
import androidx.lifecycle.lifecycleScope
import io.github.tmarsteel.hqrecorder.R
import io.github.tmarsteel.hqrecorder.databinding.FragmentSettingsBinding
import io.github.tmarsteel.hqrecorder.ui.StringSpinnerAdapter
import kotlinx.coroutines.launch
import java.text.DecimalFormat

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private var audioManager: AudioManager? = null

    private var selectedDeviceAddress: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        binding.settingsAudioDeviceSpinner.adapter = StringSpinnerAdapter<AudioDeviceInfo>(
            context = requireContext(),
            labelGetter = { "${it.productName} (${it.address})" },
            idMapper = { _, d -> d.id.toLong() },
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
                val samplingRatesAdapter = binding.settingsSamplingRateSpinner.adapter as ArrayAdapter<Int>
                samplingRatesAdapter.clear()
                samplingRatesAdapter.addAll(device.sampleRates.toList().sorted())

                val bitDepthsAdapter = binding.settingsBitDepthSpinner.adapter as ArrayAdapter<BitDepth>
                bitDepthsAdapter.clear()
                enumValues<BitDepth>()
                    .filter { it.encodingValue in device.encodings }
                    .sortedBy { it.ordinal }
                    .forEach { bitDepthsAdapter.add(it) }

                Log.i(SettingsFragment::class.simpleName, "Device ${device.address} selected with sampling rates ${device.sampleRates.contentToString()} and encodings ${device.encodings.contentToString()}")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedDeviceAddress = null
            }
        }

        (binding.settingsAudioDeviceSpinner.adapter as ArrayAdapter<AudioDeviceInfo>).addAll(*audioManager!!.getDevices(AudioManager.GET_DEVICES_INPUTS))
        audioManager!!.registerAudioDeviceCallback(audioDeviceCallback, null)

        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        _binding = null
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val binding = _binding ?: return
            addedDevices.forEach {
                if (!it.isSource) {
                    return
                }
                (binding.settingsAudioDeviceSpinner.adapter as ArrayAdapter<AudioDeviceInfo>).add(it)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val binding = _binding ?: return
            removedDevices.forEach {
                (binding.settingsAudioDeviceSpinner.adapter as ArrayAdapter<AudioDeviceInfo>).remove(it)
            }
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