package io.github.tmarsteel.hqrecorder.ui.record

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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

    companion object {


        @JvmStatic
        val TAG = "RecordFragment"
    }
}