package io.github.tmarsteel.hqrecorder.ui.record

import android.content.Context.BIND_AUTO_CREATE
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.allViews
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.tmarsteel.hqrecorder.MainActivity
import io.github.tmarsteel.hqrecorder.R
import io.github.tmarsteel.hqrecorder.databinding.FragmentRecordBinding
import io.github.tmarsteel.hqrecorder.recording.RecordingService
import io.github.tmarsteel.hqrecorder.recording.RecordingStatusServiceMessage
import io.github.tmarsteel.hqrecorder.recording.StartOrStopListeningCommand
import io.github.tmarsteel.hqrecorder.recording.UpdateRecordingConfigCommand
import io.github.tmarsteel.hqrecorder.ui.ListeningStatusSubscriber
import io.github.tmarsteel.hqrecorder.ui.RecordingConfigViewModel
import io.github.tmarsteel.hqrecorder.ui.SignalLevelIndicatorView
import io.github.tmarsteel.hqrecorder.util.CoroutineServiceCommunicator
import io.github.tmarsteel.hqrecorder.util.CoroutineServiceCommunicator.Companion.coDoWithService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RecordFragment : Fragment(), ListeningStatusSubscriber {

    private var _binding: FragmentRecordBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private val recordingConfigViewModel: RecordingConfigViewModel by activityViewModels()
    private lateinit var tracksAdapter: RecordTrackAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
           val permissions = (requireActivity() as MainActivity).coroutinePermissionRequester.requestPermissions(
                arrayOf(PERMISSION_RECORD_AUDIO),
               REQUEST_CODE_REQUEST_AUDIO_PERMISSIONS,
            )
            if (PERMISSION_RECORD_AUDIO !in permissions) {
                Toast.makeText(requireContext(), R.string.record_no_permission, Toast.LENGTH_LONG).show()
                return@launch
            }

            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                coDoWithService(
                    context = requireContext(),
                    intent = Intent(requireContext(), RecordingService::class.java),
                    flags = BIND_AUTO_CREATE,
                    immediateHandler = this@RecordFragment::tryHandleStatusUpdate,
                ) { comm ->
                    try {
                        setUpRecordingServiceAndStartListening(comm)

                        while (true) {
                            delay(1000)
                        }
                    }
                    finally {
                        comm.sendToService(StartOrStopListeningCommand.buildMessage(start = false, statusSubscription = false))
                    }
                }
            }
        }
    }

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
        tracksAdapter = RecordTrackAdapter(requireContext())
        binding.recordTrackList.adapter = tracksAdapter

        return root
    }

    override fun onResume() {
        super.onResume()

        tracksAdapter.clear()
        recordingConfigViewModel.config?.tracks?.let(tracksAdapter::addAll)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun tryHandleStatusUpdate(msg: Message): Boolean {
        val update = RecordingStatusServiceMessage.fromMessage(msg)
            ?: return false

        onStatusUpdate(update)
        return true
    }

    private suspend fun setUpRecordingServiceAndStartListening(comm: CoroutineServiceCommunicator) {
        val config = recordingConfigViewModel.config ?: run {
            Toast.makeText(requireContext(), R.string.toast_cant_listen_no_config, Toast.LENGTH_SHORT).show()
            return
        }
        val configResponse = UpdateRecordingConfigCommand.Response.fromMessage(
            comm.exchangeWithService(UpdateRecordingConfigCommand.buildMessage(config))
        )!!
        when (configResponse.result) {
            UpdateRecordingConfigCommand.Response.Result.STOP_RECORDING_FIRST -> {
                // still recording; great update UI
            }
            UpdateRecordingConfigCommand.Response.Result.INVALID -> {
                Toast.makeText(requireContext(), R.string.toast_cant_confgiure_invalid, Toast.LENGTH_LONG).show()
                return
            }
            UpdateRecordingConfigCommand.Response.Result.OK -> {
                // nothing to do, as requested
            }
        }

        val startListenResponse = StartOrStopListeningCommand.Response.fromMessage(
            comm.exchangeWithService(StartOrStopListeningCommand.buildMessage(start = true, statusSubscription = true))
        )!!
        when (startListenResponse.result) {
            StartOrStopListeningCommand.Response.Result.STILL_RECORDING -> {
                // all good, recording will be reflected in the UI
            }
            StartOrStopListeningCommand.Response.Result.NO_PERMISSION -> {
                Toast.makeText(requireContext(), R.string.toast_cant_listen_no_mic_permission, Toast.LENGTH_LONG).show()
                return
            }
            StartOrStopListeningCommand.Response.Result.NOT_CONFIGURED -> {
                Toast.makeText(requireContext(), R.string.toast_cant_listen_no_config, Toast.LENGTH_LONG).show()
                return
            }
            StartOrStopListeningCommand.Response.Result.DEVICE_NOT_AVAILABLE -> {
                Toast.makeText(requireContext(), R.string.toast_cant_listen_device_not_available, Toast.LENGTH_LONG).show()
                return
            }
            StartOrStopListeningCommand.Response.Result.NOT_LISTENING -> {
                check(false) {
                    "This shouldn't have happened"
                }
            }
            StartOrStopListeningCommand.Response.Result.LISTENING -> {
                // nothing to do, as requested
            }
        }
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
                Log.i(javaClass.name, "indicator ${System.identityHashCode(indicator)}: ${indicator.indicatesTrackId} ${indicator.indicatesLeftOrRight}")
                val trackLevels = indicator.indicatesTrackId?.let(update.trackLevels::get)
                    ?: return@forEach
                indicator.update(
                    if (indicator.indicatesLeftOrRight) trackLevels.second ?: 0.0f else trackLevels.first
                )
            }

        _binding?.let { binding ->
            binding.recordLivenessIndicator.setImageResource(if (update.isRecording) R.drawable.ic_recording else R.drawable.ic_not_recording)

            val nMinutes = update.currentTakeDuration.inWholeSeconds
            val nSeconds = update.currentTakeDuration.inWholeSeconds % 60
            binding.recordTakeLength.text = "${nMinutes.toString(10).padStart(2, '0')}:${nSeconds.toString(10).padStart(2, '0')}"
        }
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

        val REQUEST_CODE_REQUEST_AUDIO_PERMISSIONS = 1
        val PERMISSION_RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    }
}