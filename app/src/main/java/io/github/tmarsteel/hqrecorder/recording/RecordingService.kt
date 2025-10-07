package io.github.tmarsteel.hqrecorder.recording

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.*
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
import io.github.tmarsteel.hqrecorder.R
import io.github.tmarsteel.hqrecorder.util.bytesPerSecond
import io.github.tmarsteel.hqrecorder.util.minBufferSizeInBytes
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

const val NOTIFICATION_CHANNEL_ID_RECORDING_SERVICE = "recording_service"
const val NOTIFICATION_ID_RECORDING_SERVICE_FG = 1

class RecordingService : Service() {
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager

    override fun onBind(intent: Intent): IBinder {
        val handler = IncomingHandler(applicationContext.mainLooper)
        return Messenger(handler).binder
    }

    override fun onCreate() {
        notificationManager = getSystemService<NotificationManager>()!!
        val notification = createNotification(currentlyRecording = false)

        assureNotificationChannelExists()
        startForeground(NOTIFICATION_ID_RECORDING_SERVICE_FG, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        audioManager = getSystemService(AudioManager::class.java)
    }

    private fun assureNotificationChannelExists() {
        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID_RECORDING_SERVICE) != null) {
            return
        }

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID_RECORDING_SERVICE,
            getString(R.string.notification_record_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notification for the recording background task"
            enableVibration(false)
            enableLights(false)
            setSound(null, null)
        }

        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(currentlyRecording: Boolean): Notification {
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(applicationContext, R.drawable.ic_stop_24),
            getString(R.string.notification_record_action_stop),
            PendingIntent.getForegroundService(
                applicationContext,
                REQUEST_CODE_FORCE_STOP_SERVICE,
                Intent(applicationContext, RecordingService::class.java).apply {
                    setAction(ACTION_FORCE_STOP_SERVICE)
                },
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
            .setContextual(false)
            .setAuthenticationRequired(false)
            .build()

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID_RECORDING_SERVICE)
            .setContentText(getString(if (currentlyRecording) {
                R.string.notification_record_recording
            } else {
                R.string.notification_record_ready
            }))
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
            .setLocalOnly(true)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_recording_notification)
            .addAction(stopAction)
            .build()
    }

    private fun updateNotification(currentlyRecording: Boolean) {
        val notification = createNotification(currentlyRecording)
        notificationManager.notify(NOTIFICATION_ID_RECORDING_SERVICE_FG, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_FORCE_STOP_SERVICE) {
            state.forceStop()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        super.onUnbind(intent)

        state.onUnbind()
        return false
    }

    override fun onDestroy() {
        Log.i(javaClass.name, "onDestroy")
    }

    private var state: State = Initial()

    private inner class IncomingHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                UpdateRecordingConfigCommand.WHAT_VALUE -> {
                    val command = UpdateRecordingConfigCommand.fromMessage(msg)!!
                    val response = state.updateRecordingConfig(command.config)
                    Log.i(javaClass.name, "command = $command, response = $response")
                    msg.replyTo?.send(UpdateRecordingConfigCommand.Response.buildMessage(response))
                }
                StartOrStopListeningCommand.WHAT_VALUE -> {
                    val command = StartOrStopListeningCommand.fromMessage(msg)!!
                    val response = state.startOrStopListening(command, msg.replyTo)
                    Log.i(javaClass.name, "command = $command, response = $response")
                    msg.replyTo?.send(StartOrStopListeningCommand.Response.buildMessage(response))
                }
                StartNewTakeCommand.WHAT_VALUE -> {
                    val command = StartNewTakeCommand.fromMessage(msg)!!
                    val response = state.startNewTake()
                    Log.i(javaClass.name, "command = $command, response = $response")
                    msg.replyTo?.send(StartNewTakeCommand.Response.buildMessage(response))
                }
                FinishTakeCommand.WHAT_VALUE -> {
                    val command = FinishTakeCommand.fromMessage(msg)!!
                    val response = state.finishTake()
                    Log.i(javaClass.name, "command = $command, response = $response")
                    msg.replyTo?.send(FinishTakeCommand.Response.buildMessage(response))
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
        fun onUnbind()
        fun forceStop()
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

        override fun onUnbind() {
            stopSelf()
        }

        override fun forceStop() {
            stopSelf()
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
            if (audioRecord!!.routedDevice?.id != device.id) {
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

        private fun stopService() {
            dispose()
            stopSelf()
        }

        override fun onUnbind() {
            stopService()
        }

        override fun forceStop() {
            stopService()
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

        val listeningThread: Thread
        val listeningRunnable: TakeRecorderRunnable
        init {
            val (thread, runnable) = TakeRecorderRunnable.setUpNewThread(
                applicationContext,
                configuredState.config.tracks,
                configuredState.channelMask,
                audioRecord,
                statusSubscribers,
            )
            listeningThread = thread
            listeningRunnable = runnable
            listeningThread.start()
        }

        override fun updateRecordingConfig(config: RecordingConfig): UpdateRecordingConfigCommand.Response {
            if (listeningThread.isAlive && listeningRunnable.isRecording) {
                return UpdateRecordingConfigCommand.Response(
                    UpdateRecordingConfigCommand.Response.Result.STOP_RECORDING_FIRST
                )
            }

            internalStopListening(null)
            state = configuredState
            val updatedConfigResponse = state.updateRecordingConfig(config)
            if (updatedConfigResponse.result == UpdateRecordingConfigCommand.Response.Result.OK) {
                state.startOrStopListening(StartOrStopListeningCommand(start = true, statusSubscription = false), null)
                (state as? ListeningAndPossiblyRecording)?.statusSubscribers?.addAll(this.statusSubscribers)
            }
            return updatedConfigResponse
        }

        private fun internalStopListening(subscriber: Messenger?) {
            if (listeningThread.isAlive) {
                check(!listeningRunnable.isRecording)
                listeningRunnable.executeCommandSync(TakeRecorderRunnable.Command.StopListening)
            }

            state = configuredState
            val finalStatusUpdate = RecordingStatusServiceMessage.buildMessage(RecordingStatusServiceMessage(
                isListening = false,
                isRecording = false,
                loadPercentage = 0u,
                trackLevels = emptyMap(),
                currentTakeDuration = 0.milliseconds,
            ))
            (statusSubscribers + listOfNotNull(subscriber)).forEach {
                it.send(finalStatusUpdate)
            }
            updateNotification(currentlyRecording = false)
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
            }
            if (listeningRunnable.isRecording) {
                return StartOrStopListeningCommand.Response(
                    StartOrStopListeningCommand.Response.Result.STILL_RECORDING
                )
            }
            internalStopListening(subscriber)
            return StartOrStopListeningCommand.Response(
                StartOrStopListeningCommand.Response.Result.NOT_LISTENING
            )
        }

        private fun assureTakeFinished(): Boolean {
            if (!listeningThread.isAlive || !listeningRunnable.isRecording) {
                return false
            }

            listeningRunnable.executeCommandSync(TakeRecorderRunnable.Command.FinishTake)
            return true
        }

        override fun startNewTake(): StartNewTakeCommand.Response {
            val wasRecordingBefore = listeningThread.isAlive && listeningRunnable.isRecording
            assureTakeFinished()
            listeningRunnable.executeCommandSync(TakeRecorderRunnable.Command.NextTake)
            if (!wasRecordingBefore) {
                updateNotification(currentlyRecording = true)
            }
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

        override fun onUnbind() {
            if (listeningThread.isAlive && listeningRunnable.isRecording) {
                return
            }

            internalStopListening(null)
            // now in configured state
            state.onUnbind()
        }

        override fun forceStop() {
            assureTakeFinished()

            internalStopListening(null)
            // now in configured state
            state.forceStop()
        }
    }

    companion object {
        const val REQUEST_CODE_FORCE_STOP_SERVICE = 1
        val ACTION_FORCE_STOP_SERVICE = "force-stop"

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