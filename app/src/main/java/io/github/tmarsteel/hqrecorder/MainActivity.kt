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
import android.os.PersistableBundle
import android.util.Log
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.github.tmarsteel.hqrecorder.databinding.ActivityMainBinding
import io.github.tmarsteel.hqrecorder.recording.RecordingConfig
import io.github.tmarsteel.hqrecorder.ui.RecordingConfigViewModel
import io.github.tmarsteel.hqrecorder.ui.record.RecordFragment.Companion.TAG
import io.github.tmarsteel.hqrecorder.util.CoroutinePermissionRequester
import io.github.tmarsteel.hqrecorder.util.GsonInSharedPreferencesDelegate.Companion.gsonInSharedPreferences

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val recordingConfigViewModel: RecordingConfigViewModel by viewModels()

    val coroutinePermissionRequester = CoroutinePermissionRequester(this)

    private val sharedPreferences by lazy {
        getSharedPreferences(javaClass.name, MODE_PRIVATE)
    }
    private var persistentRecordingConfig: RecordingConfig by gsonInSharedPreferences(this::sharedPreferences::get, RecordingConfig.Companion::INITIAL)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        recordingConfigViewModel.config = persistentRecordingConfig

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
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        coroutinePermissionRequester.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    fun updateRecordingConfig(newValue: RecordingConfig) {
        persistentRecordingConfig = newValue
        recordingConfigViewModel.config = newValue
    }

    override fun onStop() {
        super.onStop()
    }
}