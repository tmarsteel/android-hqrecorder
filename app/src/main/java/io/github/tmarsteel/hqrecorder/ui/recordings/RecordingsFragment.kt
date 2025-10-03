package io.github.tmarsteel.hqrecorder.ui.recordings

import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import io.github.tmarsteel.hqrecorder.databinding.FragmentRecordingsBinding
import kotlin.concurrent.thread

class RecordingsFragment : Fragment() {

    private var _binding: FragmentRecordingsBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private lateinit var trackListAdapter: RecordingsTracksAdapter
    private var trackListRefreshCancellationSignal: CancellationSignal? = null
    private var trackListLoadedAtLeastOnce: Boolean = false
    private var currentRefreshThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        trackListAdapter = RecordingsTracksAdapter(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecordingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        binding.recordingsTrackList.adapter = trackListAdapter

        triggerRefresh()

        return root
    }

    override fun onResume() {
        super.onResume()

        if (!trackListLoadedAtLeastOnce && currentRefreshThread?.isAlive != true) {
            triggerRefresh()
        }
    }

    private fun triggerRefresh() {
        trackListRefreshCancellationSignal?.cancel()
        currentRefreshThread?.interrupt()

        trackListRefreshCancellationSignal = CancellationSignal()
        trackListAdapter.clear()
        binding.recordingsNoItemsInfo.visibility = View.INVISIBLE
        binding.recordingsLoadingBar.visibility = View.VISIBLE

        currentRefreshThread = thread(name = "track-list-refresh", start = true) {
            val cursor = try {
                requireContext().contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    TrackInfo.COLUMN_NAMES,
                    "owner_package_name = ?",
                    arrayOf("io.github.tmarsteel.hqrecorder"),
                    "date_added DESC",
                    trackListRefreshCancellationSignal,
                )!!
            }
            catch (_: InterruptedException) {
                trackListRefreshCancellationSignal?.cancel()
                return@thread
            }
            catch (_: OperationCanceledException) {
                return@thread
            }
            finally {
                requireActivity().runOnUiThread {
                    binding.recordingsLoadingBar.visibility = View.INVISIBLE
                }
            }

            requireActivity().runOnUiThread {
                if (cursor.moveToFirst()) {
                    binding.recordingsNoItemsInfo.visibility = View.INVISIBLE
                } else {
                    binding.recordingsNoItemsInfo.visibility = View.VISIBLE
                    binding.recordingsTrackList.visibility = View.INVISIBLE
                    return@runOnUiThread
                }

                for (columnIdx in 0 until cursor.columnCount) {
                    Log.i(javaClass.name, "Column ${cursor.getColumnName(columnIdx)}: ${cursor.getType(columnIdx)}")
                }

                do {
                    trackListAdapter.add(TrackInfo.fromMediaStoreCursor(cursor))
                    cursor.moveToNext()
                } while (!cursor.isLast)

                trackListLoadedAtLeastOnce = true
            }
        }

    }

    override fun onPause() {
        super.onPause()

        trackListRefreshCancellationSignal?.cancel()
        currentRefreshThread?.interrupt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}