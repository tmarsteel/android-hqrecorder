package io.github.tmarsteel.hqrecorder.ui

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.github.tmarsteel.hqrecorder.recording.RecordingConfig

class RecordingConfigViewModel : ViewModel() {
    var config: RecordingConfig? = null
}