package idv.markkuo.cscblebridge

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import idv.markkuo.cscblebridge.service.MainService
import idv.markkuo.cscblebridge.service.ant.AntDevice
import idv.markkuo.cscblebridge.service.ble.BleServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class LaunchActivity: AppCompatActivity(), MainFragment.ServiceStarter, MainService.MainServiceListener {

    private var mService: MainService? = null
    private var serviceIntent: Intent? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launch)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, MainFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startService()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    override fun onStop() {
        unbind()
        super.onStop()
    }

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MainService.LocalBinder
            mService = binder.service
            binder.service.addListener(this@LaunchActivity)
            val mainFragment = mainFragment()
            mainFragment?.searching(binder.service.isSearching)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mService?.removeListener(this@LaunchActivity)
            mService = null
        }
    }

    override fun startService() {
        val intent = Intent(this, MainService::class.java)
        serviceIntent = intent
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, 0)
    }

    override fun stopService() {
        runBlocking {
            withContext(Dispatchers.IO) {
                mService?.stopSearching()
                unbind()
                serviceIntent?.let { stopService(it) }
            }
        }
    }

    private fun unbind() {
        try {
            unbindService(connection)
        } catch (e: IllegalArgumentException) {
            // Expected if not bound
        }
    }

    override fun deviceSelected(antDevice: AntDevice) {
        mService?.deviceSelected(antDevice)
    }

    override fun isSearching(): Boolean = mService?.isSearching ?: false
    override fun searching(isSearching: Boolean) {
        mainFragment()?.searching(isSearching)
    }

    override fun onDevicesUpdated(devices: List<AntDevice>, selectedDevices: Map<BleServiceType, List<Int>>) {
        val mainFragment = mainFragment()
        mainFragment?.setDevices(devices, selectedDevices)
    }

    private fun mainFragment() =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as MainFragment?
}