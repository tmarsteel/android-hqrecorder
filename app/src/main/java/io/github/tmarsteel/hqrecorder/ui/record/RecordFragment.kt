package io.github.tmarsteel.hqrecorder.ui.record

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.github.tmarsteel.hqrecorder.R
import io.github.tmarsteel.hqrecorder.databinding.FragmentRecordBinding
import kotlinx.coroutines.launch

class RecordFragment : Fragment() {

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

    fun startRecordingNextTake() {
        binding.nextTakeBt.isEnabled = false
        lifecycleScope.launch {
            try {
            }
            finally {
                binding.nextTakeBt.isEnabled = true
            }
        }
    }

    private fun stopRecording() {

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