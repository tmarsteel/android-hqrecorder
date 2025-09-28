package io.github.tmarsteel.hqrecorder.recording

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.core.app.ActivityCompat
import io.github.tmarsteel.hqrecorder.util.bytesPerSecond
import io.github.tmarsteel.hqrecorder.util.minBufferSizeInBytes
import java.nio.ByteBuffer
import java.util.Objects
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class RecordingService : Service() {
    private lateinit var audioManager: AudioManager

    override fun onBind(intent: Intent): IBinder {
        val handler = IncomingHandler(applicationContext.mainLooper)
        return Messenger(handler).binder
    }

    override fun onCreate() {
        audioManager = getSystemService(AudioManager::class.java)
    }

    override fun onDestroy() {

    }

    private var state: State = Initial()

    private inner class IncomingHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                UpdateRecordingConfigCommand.WHAT_VALUE -> {
                    val command = UpdateRecordingConfigCommand.fromMessage(msg)!!
                    val response = state.updateRecordingConfig(command.config)
                    msg.replyTo?.send(UpdateRecordingConfigCommand.Response.buildMessage(response))
                }
                StartOrStopListeningCommand.WHAT_VALUE -> {
                    val command = StartOrStopListeningCommand.fromMessage(msg)!!
                    val response = if (command.start) {
                        state.startListening()
                    } else {
                        state.stopListening()
                    }
                    msg.replyTo?.send(StartOrStopListeningCommand.Response.buildMessage(response))
                }
                StartNewTakeCommand.WHAT_VALUE -> {
                    val command = StartNewTakeCommand.fromMessage(msg)!!
                    val response = state.startNewTake()
                    msg.replyTo?.send(StartNewTakeCommand.Response.buildMessage(response))
                }
                FinishTakeCommand.WHAT_VALUE -> {
                    val command = FinishTakeCommand.fromMessage(msg)!!
                    state.finishTake()
                }
            }
        }
    }

    private interface State {
        fun updateRecordingConfig(config: RecordingConfig): UpdateRecordingConfigCommand.Response
        fun startListening(): StartOrStopListeningCommand.Response
        fun stopListening(): StartOrStopListeningCommand.Response
        fun startNewTake(): StartNewTakeCommand.Response
        fun finishTake(): FinishTakeCommand.Response
    }

    private inner class Initial : State {
        override fun updateRecordingConfig(config: RecordingConfig): UpdateRecordingConfigCommand.Response {
            val device = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .find { isConfigValidFor(config, it) }
                ?: return UpdateRecordingConfigCommand.Response(
                    UpdateRecordingConfigCommand.Response.Result.INVALID
                )

            val desiredChannelIndexMask = Channel.buildIndexMask(config.tracks.flatMap { listOfNotNull(it.leftOrMonoDeviceChannel, it.rightDeviceChannel) })
            val chosenIndexMask = device.channelIndexMasks
                .filter { it and desiredChannelIndexMask == desiredChannelIndexMask }
                .minOrNull()

            if (chosenIndexMask == null) {
                // the requested channels cannot be requested from the device
                return UpdateRecordingConfigCommand.Response(
                    UpdateRecordingConfigCommand.Response.Result.INVALID
                )
            }

            this@RecordingService.state = Configured(device, config, chosenIndexMask)
            return UpdateRecordingConfigCommand.Response(UpdateRecordingConfigCommand.Response.Result.OK)
        }

        override fun startListening(): StartOrStopListeningCommand.Response {
            return StartOrStopListeningCommand.Response(
                StartOrStopListeningCommand.Response.Result.NOT_CONFIGURED
            )
        }

        override fun stopListening(): StartOrStopListeningCommand.Response {
            return StartOrStopListeningCommand.Response(
                StartOrStopListeningCommand.Response.Result.NOT_CONFIGURED
            )
        }

        override fun startNewTake(): StartNewTakeCommand.Response {
            return StartNewTakeCommand.Response(
                StartNewTakeCommand.Response.Result.INVALID_STATE
            )
        }

        override fun finishTake(): FinishTakeCommand.Response {
            return FinishTakeCommand.Response(
                FinishTakeCommand.Response.Result.INVALID_STATE
            )
        }
    }

    private inner class Configured(
        val device: AudioDeviceInfo,
        val config: RecordingConfig,
        val channelIndexMask: Int,
    ) : State {
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(config.samplingRate)
            .setEncoding(config.encoding)
            .setChannelIndexMask(channelIndexMask)
            .build()

        private var audioRecord: AudioRecord? = null

        override fun updateRecordingConfig(config: RecordingConfig): UpdateRecordingConfigCommand.Response {
            state = Initial()
            this.dispose()
            return state.updateRecordingConfig(config)
        }

        override fun startListening(): StartOrStopListeningCommand.Response {
            if (ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED) {
                return StartOrStopListeningCommand.Response(
                    StartOrStopListeningCommand.Response.Result.NO_PERMISSION
                )
            }

            if (audioRecord == null) {
                audioRecord = AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes((audioFormat.minBufferSizeInBytes * 2).coerceAtLeast(audioFormat.bytesPerSecond))
                    .build()
                audioRecord!!.preferredDevice = device
            }

            state = ListeningAndPossiblyRecording(
                this,
                audioRecord!!
            )
            return StartOrStopListeningCommand.Response(
                StartOrStopListeningCommand.Response.Result.LISTENING
            )
        }

        override fun stopListening(): StartOrStopListeningCommand.Response {
            return StartOrStopListeningCommand.Response(
                StartOrStopListeningCommand.Response.Result.NOT_LISTENING
            )
        }

        override fun startNewTake(): StartNewTakeCommand.Response {
            return StartNewTakeCommand.Response(
                StartNewTakeCommand.Response.Result.INVALID_STATE
            )
        }

        override fun finishTake(): FinishTakeCommand.Response {
            return FinishTakeCommand.Response(
                FinishTakeCommand.Response.Result.INVALID_STATE
            )
        }

        fun dispose() {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord!!.stop()
            }
            audioRecord?.release()
            audioRecord = null
        }
    }

    private inner class ListeningAndPossiblyRecording(
        private val configuredState: Configured,
        private val audioRecord: AudioRecord,
    ) : State {
        init {
            audioRecord.startRecording()
        }

        val buffer = ByteBuffer.allocateDirect(audioRecord.format.bytesPerSecond)
        var currentTakeRecordingRunnable: TakeRecorderRunnable? = null
        var currentTakeRecordingThread: Thread? = null

        override fun updateRecordingConfig(config: RecordingConfig): UpdateRecordingConfigCommand.Response {
            return UpdateRecordingConfigCommand.Response(
                UpdateRecordingConfigCommand.Response.Result.STOP_RECORDING_FIRST
            )
        }

        override fun startListening(): StartOrStopListeningCommand.Response {
            return StartOrStopListeningCommand.Response(
                StartOrStopListeningCommand.Response.Result.LISTENING
            )
        }

        override fun stopListening(): StartOrStopListeningCommand.Response {
            audioRecord.stop()
            state = configuredState
            return StartOrStopListeningCommand.Response(
                StartOrStopListeningCommand.Response.Result.NOT_LISTENING
            )
        }

        private fun assureTakeFinished(): Boolean {
            val thread = currentTakeRecordingThread
            if (thread == null || !thread.isAlive) {
                return false
            }

            currentTakeRecordingRunnable!!.stop()
            thread.join()
            return true
        }

        override fun startNewTake(): StartNewTakeCommand.Response {
            assureTakeFinished()

            val runnable = TakeRecorderRunnable(
                applicationContext,
                configuredState.config.tracks,
                audioRecord,
                buffer,
            )
            val thread = Thread(runnable)
            thread.start()
            currentTakeRecordingRunnable = runnable
            currentTakeRecordingThread = thread
            return StartNewTakeCommand.Response(
                StartNewTakeCommand.Response.Result.RECORDING
            )
        }

        override fun finishTake(): FinishTakeCommand.Response {
            val wasRecording = assureTakeFinished()
            return if (wasRecording) {
                FinishTakeCommand.Response(
                    FinishTakeCommand.Response.Result.FINISHED
                )
            } else {
                FinishTakeCommand.Response(
                    FinishTakeCommand.Response.Result.NOT_RECORDING
                )
            }
        }
    }

    companion object {
        private fun isConfigValidFor(config: RecordingConfig, device: AudioDeviceInfo): Boolean {
            if (device.id != config.deviceId || config.deviceAddress != device.address) {
                return false
            }

            if (config.encoding !in device.encodings) {
                return false
            }

            if (config.samplingRate !in device.sampleRates) {
                return false
            }

            val maxAvailableChannel = Channel(device.channelCounts.max())
            val maxRequestedChannel = config.tracks.maxOfOrNull { it.leftOrMonoDeviceChannel.coerceAtLeast(it.rightDeviceChannel) }
            if (maxRequestedChannel == null || maxRequestedChannel > maxAvailableChannel) {
                return false
            }

            return true
        }
    }
}