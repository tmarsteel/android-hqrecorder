package io.github.tmarsteel.hqrecorder

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import android.widget.Toast
import androidx.activity.viewModels
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import io.github.tmarsteel.hqrecorder.databinding.ActivityMainBinding
import io.github.tmarsteel.hqrecorder.recording.FinishTakeCommand
import io.github.tmarsteel.hqrecorder.recording.RecordingService
import io.github.tmarsteel.hqrecorder.recording.RecordingStatusServiceMessage
import io.github.tmarsteel.hqrecorder.recording.StartNewTakeCommand
import io.github.tmarsteel.hqrecorder.recording.StartOrStopListeningCommand
import io.github.tmarsteel.hqrecorder.recording.UpdateRecordingConfigCommand
import io.github.tmarsteel.hqrecorder.ui.ListeningStatusSubscriber
import io.github.tmarsteel.hqrecorder.ui.RecordingConfigViewModel
import io.github.tmarsteel.hqrecorder.ui.record.RecordFragment.Companion.TAG
import kotlin.getValue

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val recordingConfigViewModel: RecordingConfigViewModel by viewModels()

    private lateinit var recordingServiceResponseChannelMessenger: Messenger
    private var recordingServiceMessenger: Messenger? = null
    private var assumeServiceIsListening = false
    private val recordingServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            recordingServiceMessenger = Messenger(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            recordingServiceMessenger = null
            if (assumeServiceIsListening) {
                listeningSubscribers.forEach {
                    it.onListeningStopped()
                }
            }
            assumeServiceIsListening = false
        }
    }

    private val listeningSubscribers = mutableSetOf<ListeningStatusSubscriber>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView

        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_record,
                R.id.navigation_recordings,
                R.id.navigation_settings
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        recordingServiceResponseChannelMessenger = Messenger(ServiceIncomingHandler(mainLooper))
    }

    private fun bindRecordingService() {
        val recordingServiceIntent = Intent(this, RecordingService::class.java)
        bindService(
            recordingServiceIntent,
            recordingServiceConnection,
            BIND_AUTO_CREATE,
        )
    }

    override fun onStart() {
        super.onStart()

        if (ActivityCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf("android.permission.RECORD_AUDIO"),
                REQUEST_CODE_PERMISSION_RECORD_AUDIO_ON_NEXT_TAKE,
            )
            return
        }

        bindRecordingService()
    }

    fun registerListeningSubscriber(sub: ListeningStatusSubscriber) {
        val isFirst = listeningSubscribers.isEmpty()
        listeningSubscribers.add(sub)

        if (isFirst) {
            tryStartListening()
        }
    }

    fun unregisterListeningSubscriber(sub: ListeningStatusSubscriber) {
        listeningSubscribers.remove(sub)
        if (listeningSubscribers.isEmpty()) {
            tryStopListening()
        }
    }

    private fun tryStartListening() {
        if (assumeServiceIsListening) {
            return
        }

        if (recordingServiceMessenger == null) {
            // not yet connected, will start listening once its connected
            return
        }

        if (recordingConfigViewModel.config == null) {
            // no config exists yet, will start listening once its set
            return
        }
        check(!assumeServiceIsListening)
        check(this::recordingServiceResponseChannelMessenger.isInitialized)
        check(recordingConfigViewModel.config != null)

        if (ActivityCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf("android.permission.RECORD_AUDIO"),
                REQUEST_CODE_PERMISSION_RECORD_AUDIO_ON_NEXT_TAKE,
            )
            return
        }

        val listenCommand = StartOrStopListeningCommand.buildMessage(start = true, statusSubscription = true)
        listenCommand.replyTo = recordingServiceResponseChannelMessenger
        recordingServiceMessenger!!.send(listenCommand)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_CODE_PERMISSION_RECORD_AUDIO_ON_NEXT_TAKE -> {
                assert(permissions.single() == "android.permission.RECORD_AUDIO")
                when (grantResults.single()) {
                    PackageManager.PERMISSION_GRANTED -> {
                        Log.i(TAG, "Got microphone permission")
                        bindRecordingService()
                    }
                    PackageManager.PERMISSION_DENIED -> {
                        Log.i(TAG, "Microphone permission was denied")
                        Toast.makeText(this, R.string.record_no_permission, Toast.LENGTH_LONG).show()
                    }
                }
            }
            else -> {
                Log.e(TAG, "Unknown request code in onRequestPermissionsResult: $requestCode")
            }
        }
    }

    private fun tryStopListening() {
        if (!assumeServiceIsListening) {
            return
        }

        if (recordingServiceMessenger == null) {
            assumeServiceIsListening = false
            return
        }

        val listenCommand = StartOrStopListeningCommand.buildMessage(start = false, statusSubscription = false)
        listenCommand.replyTo = recordingServiceResponseChannelMessenger
        recordingServiceMessenger!!.send(listenCommand)
        assumeServiceIsListening = false
    }

    override fun onStop() {
        super.onStop()

        unbindService(recordingServiceConnection)
    }

    private inner class ServiceIncomingHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                FinishTakeCommand.Response.WHAT_VALUE -> {
                    Toast.makeText(this@MainActivity, R.string.toast_take_saved, Toast.LENGTH_LONG).show()
                }
                StartNewTakeCommand.Response.WHAT_VALUE -> {
                    val response = StartNewTakeCommand.Response.fromMessage(msg)!!
                    when (response.result) {
                        StartNewTakeCommand.Response.Result.INVALID_STATE -> {
                            Toast.makeText(this@MainActivity, R.string.toast_cant_listen_invalid_config, Toast.LENGTH_LONG).show()
                        }
                        StartNewTakeCommand.Response.Result.RECORDING -> {
                            // nothing to do, as requested
                        }
                    }
                }
                StartOrStopListeningCommand.Response.WHAT_VALUE -> {
                    val response = StartOrStopListeningCommand.Response.fromMessage(msg)!!
                    when (response.result) {
                        StartOrStopListeningCommand.Response.Result.STILL_RECORDING -> {
                            Toast.makeText(this@MainActivity, R.string.toast_cant_listen_still_recording, Toast.LENGTH_LONG).show()
                        }
                        StartOrStopListeningCommand.Response.Result.NO_PERMISSION -> {
                            Toast.makeText(this@MainActivity, R.string.toast_cant_listen_no_mic_permission, Toast.LENGTH_LONG).show()
                        }
                        StartOrStopListeningCommand.Response.Result.NOT_CONFIGURED -> {
                            Toast.makeText(this@MainActivity, R.string.toast_cant_listen_no_config, Toast.LENGTH_LONG).show()
                        }
                        StartOrStopListeningCommand.Response.Result.DEVICE_NOT_AVAILABLE -> {
                            Toast.makeText(this@MainActivity, R.string.toast_cant_listen_device_not_available, Toast.LENGTH_LONG).show()
                        }
                        StartOrStopListeningCommand.Response.Result.LISTENING,
                        StartOrStopListeningCommand.Response.Result.NOT_LISTENING -> {
                            // nothing to do, as requested
                        }
                    }
                }
                UpdateRecordingConfigCommand.Response.WHAT_VALUE -> {
                    val response = UpdateRecordingConfigCommand.Response.fromMessage(msg)!!
                    when (response.result) {
                        UpdateRecordingConfigCommand.Response.Result.STOP_RECORDING_FIRST -> {
                            Toast.makeText(this@MainActivity, R.string.toast_cant_confgiure_still_recording, Toast.LENGTH_LONG).show()
                        }
                        UpdateRecordingConfigCommand.Response.Result.INVALID -> {
                            Toast.makeText(this@MainActivity, R.string.toast_cant_confgiure_invalid, Toast.LENGTH_LONG).show()
                        }
                        UpdateRecordingConfigCommand.Response.Result.OK -> {
                            // nothing to do, as requested
                        }
                    }
                }
                RecordingStatusServiceMessage.WHAT_VALUE -> {
                    val message = RecordingStatusServiceMessage.fromMessage(msg)!!
                    if (message.isListening) {
                        if (!assumeServiceIsListening) {
                            listeningSubscribers.forEach {
                                it.onListeningStarted()
                            }
                        }
                        assumeServiceIsListening = true
                    } else {
                        if (assumeServiceIsListening) {
                            listeningSubscribers.forEach {
                                it.onListeningStopped()
                            }
                        }
                        assumeServiceIsListening = false
                    }

                    listeningSubscribers.forEach {
                        it.onStatusUpdate(message)
                    }
                }
            }
        }
    }

    companion object {
        const val REQUEST_CODE_PERMISSION_RECORD_AUDIO_ON_NEXT_TAKE: Int = 1
    }
}