package io.github.tmarsteel.hqrecorder.ui.record

import android.graphics.drawable.Icon
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.allViews
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.github.tmarsteel.hqrecorder.MainActivity
import io.github.tmarsteel.hqrecorder.R
import io.github.tmarsteel.hqrecorder.databinding.FragmentRecordBinding
import io.github.tmarsteel.hqrecorder.recording.RecordingStatusServiceMessage
import io.github.tmarsteel.hqrecorder.ui.ListeningStatusSubscriber
import io.github.tmarsteel.hqrecorder.ui.SignalLevelIndicatorView
import kotlinx.coroutines.launch

class RecordFragment : Fragment(), ListeningStatusSubscriber {

    private var _binding: FragmentRecordBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecordBinding.inflate(inflater, container, false)
        val root: View = binding.root

        binding.recordNextTakeBt.setOnClickListener {
            this.startRecordingNextTake()
        }
        binding.recordFinishBt.setOnClickListener {
            this.stopRecording()
        }

        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()

        (requireActivity() as MainActivity).registerListeningSubscriber(this)
    }

    override fun onPause() {
        super.onPause()

        (requireActivity() as MainActivity).unregisterListeningSubscriber(this)
    }

    override fun onListeningStarted() {
        view?.allViews
            ?.filterIsInstance<SignalLevelIndicatorView>()
            ?.forEach { it.reset() }
    }

    override fun onStatusUpdate(update: RecordingStatusServiceMessage) {
        view?.allViews
            ?.filterIsInstance<SignalLevelIndicatorView>()
            ?.forEach { indicator ->
                val trackLevels = indicator.indicatesTrackId?.let(update.trackLevels::get)
                    ?: return@forEach
                if (indicator.indicatesLeftOrRight) {
                    indicator.sampleValue = trackLevels.second ?: 0.0f
                } else {
                    indicator.sampleValue = trackLevels.first
                }
            }

        binding.recordLivenessIndicator.setImageResource(if (update.isRecording) R.drawable.ic_recording else R.drawable.ic_not_recording)

        val nMinutes = update.currentTakeDuration.inWholeSeconds
        val nSeconds = update.currentTakeDuration.inWholeSeconds % 60
        binding.recordTakeLength.text = "${nMinutes.toString(10).padStart(2, '0')}:${nSeconds.toString(10).padStart(2,  '0')}"
    }

    override fun onListeningStopped() {
        view?.allViews
            ?.filterIsInstance<SignalLevelIndicatorView>()
            ?.forEach { it.sampleValue = 0.0f }
    }

    fun startRecordingNextTake() {
        binding.recordNextTakeBt.isEnabled = false
        lifecycleScope.launch {
            try {
            }
            finally {
                binding.recordNextTakeBt.isEnabled = true
            }
        }
    }

    private fun stopRecording() {

    }

    companion object {
        @JvmStatic
        val TAG = "RecordFragment"
    }
}