package io.github.tmarsteel.hqrecorder.ui

import io.github.tmarsteel.hqrecorder.recording.RecordingStatusServiceMessage

interface ListeningStatusSubscriber {
    fun onListeningStarted()
    fun onStatusUpdate(update: RecordingStatusServiceMessage)
    fun onListeningStopped()
}