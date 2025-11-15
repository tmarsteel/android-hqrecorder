package io.github.tmarsteel.hqrecorder.ui.record

import android.Manifest
import android.content.Context.BIND_AUTO_CREATE
import android.content.Intent
import android.os.Bundle
import android.os.Message
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.allViews
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import io.github.tmarsteel.hqrecorder.MainActivity
import io.github.tmarsteel.hqrecorder.R
import io.github.tmarsteel.hqrecorder.databinding.FragmentRecordBinding
import io.github.tmarsteel.hqrecorder.recording.FinishTakeCommand
import io.github.tmarsteel.hqrecorder.recording.RecordingService
import io.github.tmarsteel.hqrecorder.recording.RecordingStatusServiceMessage
import io.github.tmarsteel.hqrecorder.recording.RetainTakeCommand
import io.github.tmarsteel.hqrecorder.recording.StartNewTakeCommand
import io.github.tmarsteel.hqrecorder.recording.StartOrStopListeningCommand
import io.github.tmarsteel.hqrecorder.recording.UpdateRecordingConfigCommand
import io.github.tmarsteel.hqrecorder.ui.RecordingConfigViewModel
import io.github.tmarsteel.hqrecorder.ui.SignalLevelIndicator
import io.github.tmarsteel.hqrecorder.util.CoroutineServiceCommunicator
import io.github.tmarsteel.hqrecorder.util.CoroutineServiceCommunicator.Companion.coDoWithService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue

class RecordFragment : Fragment() {

    private var _binding: FragmentRecordBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private val recordingConfigViewModel: RecordingConfigViewModel by activityViewModels()
    private lateinit var tracksAdapter: RecordTrackAdapter

    private val serviceInteractionQueue = LinkedBlockingQueue<suspend (CoroutineServiceCommunicator) -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
           val permissions = (requireActivity() as MainActivity).coroutinePermissionRequester.requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS),
               REQUEST_CODE_REQUEST_AUDIO_PERMISSIONS,
            )
            if (Manifest.permission.RECORD_AUDIO !in permissions) {
                Toast.makeText(requireContext(), R.string.record_no_audio_permission, Toast.LENGTH_LONG).show()
                return@launch
            }

            if (Manifest.permission.POST_NOTIFICATIONS !in permissions) {
                Toast.makeText(requireContext(), R.string.record_no_notification_permission, Toast.LENGTH_LONG).show()
            }

            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                coDoWithService(
                    context = requireContext(),
                    intent = Intent(requireContext(), RecordingService::class.java),
                    flags = BIND_AUTO_CREATE,
                    startBeforeBind = true,
                    immediateHandler = this@RecordFragment::tryHandleStatusUpdate,
                ) { comm ->
                    try {
                        setUpRecordingServiceAndStartListening(comm)

                        while (true) {
                            val nextTask = serviceInteractionQueue.poll()
                            if (nextTask != null) {
                                nextTask(comm)
                            } else {
                                delay(50)
                            }
                        }
                    }
                    finally {
                        comm.sendToService(StartOrStopListeningCommand.buildMessage(
                            listen = StartOrStopListeningCommand.ListeningAction.STOP,
                            statusUpdates = StartOrStopListeningCommand.SubscriptionAction.UNSUBSCRIBE,
                        ))
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

        setControlsEnabled(true)

        binding.recordControl.recordStartStopBt.setOnClickListener {
            this.startOrStop()
        }
        binding.recordControl.recordSaveAndNextBt.setOnClickListener {
            this.finishTake(true)
            this.startRecordingNextTake()
        }
        binding.recordControl.recordDiscardAndNextBt.setOnClickListener {
            this.finishTake(false)
            this.startRecordingNextTake()
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
            comm.exchangeWithService(StartOrStopListeningCommand.buildMessage(
                listen = StartOrStopListeningCommand.ListeningAction.START,
                statusUpdates = StartOrStopListeningCommand.SubscriptionAction.SUBSCRIBE,
            ))
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

    private var isRecording = false
    private val bufferForIndicatorUpdate = FloatArray(2)
    private fun onStatusUpdate(update: RecordingStatusServiceMessage) {
        isRecording = update.isRecording

        view?.allViews
            ?.filterIsInstance<SignalLevelIndicator>()
            ?.forEach { indicator ->
                val trackLevels = indicator.indicatesTrackId?.let(update.trackLevels::get)
                    ?: return@forEach

                bufferForIndicatorUpdate[0] = trackLevels.first
                val rightChannelSample = trackLevels.second
                if (rightChannelSample == null) {
                    indicator.update(bufferForIndicatorUpdate, 0, 1)
                } else {
                    bufferForIndicatorUpdate[1] = rightChannelSample
                    indicator.update(bufferForIndicatorUpdate, 0, 2)
                }
            }

        _binding?.let { binding ->
            binding.recordControl.recordStartStopBt.text = getString(if (isRecording) R.string.record_stop else R.string.record_start)
            binding.recordControl.recordStartStopBt.setCompoundDrawables(
                AppCompatResources.getDrawable(requireContext(), if (isRecording) R.drawable.ic_stop_24 else R.drawable.ic_mic_24),
                null,
                null,
                null,
            )
            binding.recordControl.recordSaveAndNextBt.isEnabled = isRecording
            binding.recordControl.recordDiscardAndNextBt.isEnabled = isRecording

            binding.recordControl.recordLivenessIndicator.setImageResource(if (update.isRecording) R.drawable.ic_recording else R.drawable.ic_not_recording)

            val nMinutes = update.currentTakeDuration.inWholeMinutes
            val nSeconds = update.currentTakeDuration.inWholeSeconds % 60
            binding.recordControl.recordTakeLength.text = "${nMinutes.toString(10).padStart(2, '0')}:${nSeconds.toString(10).padStart(2, '0')}"
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.recordControl.recordStartStopBt.isEnabled = enabled
        binding.recordControl.recordSaveAndNextBt.isEnabled = enabled && isRecording
        binding.recordControl.recordDiscardAndNextBt.isEnabled = enabled && isRecording
    }

    private fun finishTake(saveImmediately: Boolean) {
        serviceInteractionQueue.put { comm ->
            val response = FinishTakeCommand.Response.fromMessage(
                comm.exchangeWithService(FinishTakeCommand.buildMessage())
            )
            when (response.result) {
                FinishTakeCommand.Response.Result.TAKE_FINISHED -> {
                    if (saveImmediately) {
                        comm.exchangeWithService(RetainTakeCommand.buildMessage(response.takeResult.moveToMediaStoreId!!))
                    } else {
                        Snackbar.make(binding.root, R.string.toast_take_discarded, Snackbar.LENGTH_LONG)
                            .setAction(R.string.toast_undo_take_discard, {
                                serviceInteractionQueue.put { comm ->
                                    comm.exchangeWithService(RetainTakeCommand.buildMessage(response.takeResult.moveToMediaStoreId!!))
                                }
                            })
                            .show()
                    }
                }

                FinishTakeCommand.Response.Result.INVALID_STATE -> {
                    Toast.makeText(requireContext(), R.string.toast_cant_finish_not_recording, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    private fun startRecordingNextTake() {
        serviceInteractionQueue.put { comm ->
            val response = StartNewTakeCommand.Response.fromMessage(
                comm.exchangeWithService(StartNewTakeCommand.buildMessage())
            )!!
            when (response.result) {
                StartNewTakeCommand.Response.Result.RECORDING -> {
                    // all good, as requested
                }

                StartNewTakeCommand.Response.Result.INVALID_STATE -> {
                    Toast.makeText(requireContext(), R.string.toast_cant_listen_invalid_config, Toast.LENGTH_LONG).show()
                }

                StartNewTakeCommand.Response.Result.ALREADY_RECORDING -> {
                    Toast.makeText(requireContext(), R.string.toast_cant_start_take_already_recording, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startOrStop() {
        if (isRecording) {
            finishTake(saveImmediately = true)
        } else {
            startRecordingNextTake()
        }
    }

    companion object {
        @JvmStatic
        val TAG = "RecordFragment"

        val REQUEST_CODE_REQUEST_AUDIO_PERMISSIONS = 1
        val PERMISSION_RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    }
}