package io.github.tmarsteel.hqrecorder.ui.record

import android.Manifest
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Bundle
import android.os.IBinder
import android.os.Messenger
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.github.tmarsteel.hqrecorder.R
import io.github.tmarsteel.hqrecorder.databinding.FragmentRecordBinding
import io.github.tmarsteel.hqrecorder.util.FloatCollectors
import io.github.tmarsteel.hqrecorder.util.minBufferSizeInBytes
import io.github.tmarsteel.hqrecorder.util.stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.math.absoluteValue
import kotlin.time.Duration.Companion.milliseconds

class RecordFragment : Fragment() {

    private var _binding: FragmentRecordBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private var recordingServiceMessenger: Messenger? = null
    private val recordingServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            name: ComponentName?,
            service: IBinder?
        ) {
            service?.let {
                recordingServiceMessenger = Messenger(it)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingServiceMessenger = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val recordViewModel =
            ViewModelProvider(this).get(RecordViewModel::class.java)

        _binding = FragmentRecordBinding.inflate(inflater, container, false)
        val root: View = binding.root

        binding.nextTakeBt.setOnClickListener {
            this.startRecordingNextTake()
        }
        binding.finishBt.setOnClickListener {
            this.stopRecording()
        }

        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private var audioRecord: AudioRecord? = null

    private val audioFormat = AudioFormat.Builder()
        .setSampleRate(92000)
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()

    fun startRecordingNextTake() {
        binding.levelIndicator.clipIndicator = false

        val buffer = ByteBuffer.allocateDirect(audioFormat.frameSizeInBytes * audioFormat.sampleRate)

        if (audioRecord == null) {
            if (ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(TAG, "Requesting microphone permission")
                ActivityCompat.requestPermissions(requireActivity(), arrayOf("android.permission.RECORD_AUDIO"), REQUEST_CODE_PERMISSION_RECORD_AUDIO_ON_NEXT_TAKE)
                return
            }

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes((audioFormat.minBufferSizeInBytes * 2).coerceAtLeast(buffer.capacity() * 2))
                .build()
        }
        audioRecord!!.startRecording()
        binding.levelIndicator.reset()

        lifecycleScope.launch {
            Log.i(TAG, "Starting take")
            while (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                buffer.clear()
                val readResult = audioRecord!!.read(buffer, buffer.capacity(), AudioRecord.READ_NON_BLOCKING)
                check(readResult >= 0)
                buffer.limit(readResult)
                val asFloatBuffer = buffer.asFloatBuffer()
                val maxSample = asFloatBuffer.stream().map { it.absoluteValue }.collect(FloatCollectors.MAXING)
                binding.levelIndicator.update(maxSample)

                if (buffer.limit() < buffer.capacity()) {
                    // temporarily exhausted all audio data
                    delay(100.milliseconds)
                }
            }
            Log.i(TAG, "Take ended")
        }
    }

    private fun stopRecording() {
        audioRecord?.stop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQUEST_CODE_PERMISSION_RECORD_AUDIO_ON_NEXT_TAKE -> {
                assert(permissions.single() == "android.permission.RECORD_AUDIO")
                when (grantResults.single()) {
                    PackageManager.PERMISSION_GRANTED -> {
                        Log.i(TAG, "Got microphone permission, starting recording")
                        startRecordingNextTake()
                    }
                    PackageManager.PERMISSION_DENIED -> {
                        Log.i(TAG, "Microphone permission was denied")
                        Toast.makeText(requireContext(), R.string.record_no_permission, Toast.LENGTH_LONG).show()
                    }
                }
            }
            else -> {
                Log.e(TAG, "Unknown request code in onRequestPermissionsResult: $requestCode")
            }
        }
    }

    companion object {
        const val REQUEST_CODE_PERMISSION_RECORD_AUDIO_ON_NEXT_TAKE: Int = 1

        @JvmStatic
        val TAG = "RecordFragment"
    }
}