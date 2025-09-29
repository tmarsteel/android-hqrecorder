package io.github.tmarsteel.hqrecorder

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.activity.viewModels
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import io.github.tmarsteel.hqrecorder.databinding.ActivityMainBinding
import io.github.tmarsteel.hqrecorder.recording.RecordingService
import io.github.tmarsteel.hqrecorder.recording.RecordingStatusServiceMessage
import io.github.tmarsteel.hqrecorder.recording.StartOrStopListeningCommand
import io.github.tmarsteel.hqrecorder.recording.UpdateRecordingConfigCommand
import io.github.tmarsteel.hqrecorder.ui.ListeningStatusSubscriber
import io.github.tmarsteel.hqrecorder.ui.RecordingConfigViewModel
import kotlin.getValue

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val recordingConfigViewModel: RecordingConfigViewModel by viewModels()

    private var assumeServiceIsListening = false
    private lateinit var recordingServiceResponseChannelMessenger: Messenger
    private var recordingServiceMessenger: Messenger? = null
    private val recordingServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            recordingServiceMessenger = Messenger(service)
            if (listeningSubscribers.isNotEmpty()) {
                if (recordingConfigViewModel.config.isInitialized) {
                    tryStartListening()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            recordingServiceMessenger = null
            if (assumeServiceIsListening) {
                listeningSubscribers.forEach {
                    it.onListeningStopped()
                }
            }
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
        recordingConfigViewModel.config.observe(this) {
            if (assumeServiceIsListening) {
                tryStopListening()
            }
            recordingServiceMessenger?.send(UpdateRecordingConfigCommand.buildMessage(it))
            if (listeningSubscribers.isNotEmpty()) {
                tryStartListening()
            }
        }
    }

    override fun onStart() {
        super.onStart()

        val recordingServiceIntent = Intent(this, RecordingService::class.java)
        bindService(
            recordingServiceIntent,
            recordingServiceConnection,
            BIND_AUTO_CREATE,
        )
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

        if (!recordingConfigViewModel.config.isInitialized) {
            // no config exists yet, will start listening once its set
            return
        }
        check(!assumeServiceIsListening)
        check(this::recordingServiceResponseChannelMessenger.isInitialized)
        check(recordingConfigViewModel.config.isInitialized)

        val listenCommand = StartOrStopListeningCommand.buildMessage(start = true, statusSubscription = true)
        listenCommand.replyTo = recordingServiceResponseChannelMessenger
        recordingServiceMessenger!!.send(listenCommand)
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
    }

    override fun onStop() {
        super.onStop()

        unbindService(recordingServiceConnection)
    }

    private inner class ServiceIncomingHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
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
}