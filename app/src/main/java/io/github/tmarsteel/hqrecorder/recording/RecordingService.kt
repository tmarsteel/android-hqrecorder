package io.github.tmarsteel.hqrecorder.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
import io.github.tmarsteel.hqrecorder.R
import io.github.tmarsteel.hqrecorder.util.bytesPerSecond
import io.github.tmarsteel.hqrecorder.util.minBufferSizeInBytes
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

const val NOTIFICATION_CHANNEL_RECORDING_SERVICE = "recording_service"
const val NOTIFICATION_ID_RECORDING_SERVICE_FG = 1

class RecordingService : Service() {
    private lateinit var audioManager: AudioManager

    override fun onBind(intent: Intent): IBinder {
        val handler = IncomingHandler(applicationContext.mainLooper)
        return Messenger(handler).binder
    }

    override fun onCreate() {
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_RECORDING_SERVICE, NOTIFICATION_CHANNEL_RECORDING_SERVICE, NotificationManager.IMPORTANCE_NONE).also {
            it.description = "Notification for the recording background task"
        }
        getSystemService<NotificationManager>()!!.createNotificationChannel(channel)
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_RECORDING_SERVICE)
            .setContentTitle(getString(R.string.notification_record_ready))
            .build()
        startForeground(NOTIFICATION_ID_RECORDING_SERVICE_FG, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        audioManager = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
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
                    val response = state.startOrStopListening(command, msg.replyTo)
                    msg.replyTo?.send(StartOrStopListeningCommand.Response.buildMessage(response))
                }
                StartNewTakeCommand.WHAT_VALUE -> {
                    val command = StartNewTakeCommand.fromMessage(msg)!!
                    val response = state.startNewTake()
                    msg.replyTo?.send(StartNewTakeCommand.Response.buildMessage(response))
                }
                FinishTakeCommand.WHAT_VALUE -> {
                    val command = FinishTakeCommand.fromMessage(msg)!!
                    val response = state.finishTake()
                    msg.replyTo?.send(FinishTakeCommand.buildMessage())
                }
                else -> {
                    Log.e(javaClass.name, "Unrecognized message $msg")
                }
            }
        }
    }

    private interface State {
        fun updateRecordingConfig(config: RecordingConfig): UpdateRecordingConfigCommand.Response
        fun startOrStopListening(command: StartOrStopListeningCommand, subscriber: Messenger?): StartOrStopListeningCommand.Response
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

            val desiredChannelMask = ChannelMask.forChannels(config.tracks.flatMap { listOfNotNull(it.leftOrMonoDeviceChannel, it.rightDeviceChannel) })
            val chosenChannelMask = device.channelMasks
                .map(::ChannelMask)
                .filter { desiredChannelMask in it }
                .minOrNull()

            if (chosenChannelMask == null) {
                // the requested channels cannot be requested from the device
                return UpdateRecordingConfigCommand.Response(
                    UpdateRecordingConfigCommand.Response.Result.INVALID
                )
            }

            this@RecordingService.state = Configured(device, config, chosenChannelMask)
            return UpdateRecordingConfigCommand.Response(UpdateRecordingConfigCommand.Response.Result.OK)
        }

        override fun startOrStopListening(command: StartOrStopListeningCommand, subscriber: Messenger?): StartOrStopListeningCommand.Response {
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
        val channelMask: ChannelMask,
    ) : State {
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(config.samplingRate)
            .setEncoding(config.encoding)
            .setChannelMask(channelMask.mask)
            .build()

        private var audioRecord: AudioRecord? = null

        private val statusSubscribers = Collections.newSetFromMap<Messenger>(ConcurrentHashMap())

        override fun updateRecordingConfig(config: RecordingConfig): UpdateRecordingConfigCommand.Response {
            state = Initial()
            this.dispose()
            return state.updateRecordingConfig(config)
        }

        override fun startOrStopListening(command: StartOrStopListeningCommand, subscriber: Messenger?): StartOrStopListeningCommand.Response {
            subscriber?.let(if (command.start) statusSubscribers::add else statusSubscribers::remove)

            if (!command.start) {
                return StartOrStopListeningCommand.Response(
                    StartOrStopListeningCommand.Response.Result.NOT_LISTENING
                )
            }

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

            audioRecord!!.startRecording()
            if (audioRecord!!.routedDevice.id != device.id) {
                audioRecord!!.stop()
                audioRecord!!.release()
                audioRecord = null
                return StartOrStopListeningCommand.Response(
                    StartOrStopListeningCommand.Response.Result.DEVICE_NOT_AVAILABLE
                )
            }

            state = ListeningAndPossiblyRecording(
                this,
                audioRecord!!,
                statusSubscribers,
            )
            return StartOrStopListeningCommand.Response(
                StartOrStopListeningCommand.Response.Result.LISTENING
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
        private val statusSubscribers: MutableSet<Messenger>,
    ) : State {
        init {
            audioRecord.startRecording()
        }

        val listeningRunnable = TakeRecorderRunnable(
            applicationContext,
            configuredState.config.tracks,
            configuredState.channelMask,
            audioRecord,
            statusSubscribers,
        )
        val listeningThread: Thread = Thread(listeningRunnable)

        init {
            listeningThread.start()
        }

        override fun updateRecordingConfig(config: RecordingConfig): UpdateRecordingConfigCommand.Response {
            return UpdateRecordingConfigCommand.Response(
                UpdateRecordingConfigCommand.Response.Result.STOP_RECORDING_FIRST
            )
        }

        override fun startOrStopListening(
            command: StartOrStopListeningCommand,
            subscriber: Messenger?
        ): StartOrStopListeningCommand.Response {
            subscriber?.let(if (command.start) statusSubscribers::add else statusSubscribers::remove)

            if (command.start) {
                return StartOrStopListeningCommand.Response(
                    StartOrStopListeningCommand.Response.Result.LISTENING
                )
            } else {
                audioRecord.stop()
                state = configuredState
                val finalStatusUpdate = RecordingStatusServiceMessage.buildMessage(RecordingStatusServiceMessage(
                    isListening = false,
                    isRecording = false,
                    loadPercentage = 0u,
                    trackLevels = emptyMap(),
                ))
                (statusSubscribers + listOfNotNull(subscriber)).forEach {
                    it.send(finalStatusUpdate)
                }
                return StartOrStopListeningCommand.Response(
                    StartOrStopListeningCommand.Response.Result.NOT_LISTENING
                )
            }
        }

        private fun assureTakeFinished(): Boolean {
            val localThread = listeningThread
            if (localThread == null || !localThread.isAlive) {
                return false
            }

            val localRunnable = listeningRunnable
            if (localRunnable == null || !localRunnable.isRecording) {
                return false
            }

            listeningRunnable!!.sendCommand(TakeRecorderRunnable.Command.FINISH_TAKE)
            return true
        }

        override fun startNewTake(): StartNewTakeCommand.Response {
            assureTakeFinished()
            listeningRunnable!!.sendCommand(TakeRecorderRunnable.Command.NEXT_TAKE)
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

            if (config.channelMask.mask !in device.channelMasks) {
                return false
            }

            return true
        }
    }
}