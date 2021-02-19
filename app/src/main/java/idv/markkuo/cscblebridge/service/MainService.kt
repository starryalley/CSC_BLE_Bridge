package idv.markkuo.cscblebridge.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import idv.markkuo.cscblebridge.LaunchActivity
import idv.markkuo.cscblebridge.R
import idv.markkuo.cscblebridge.service.ant.AntDevice
import idv.markkuo.cscblebridge.service.ble.BleServiceType

class MainService : Service() {

    companion object {
        private const val CHANNEL_DEFAULT_IMPORTANCE = "csc_ble_channel"
        private const val MAIN_CHANNEL_NAME = "CscService"
        private const val ONGOING_NOTIFICATION_ID = 9999
        private const val STOP_SELF_ACTION = "stop_self"
    }

    interface MainServiceListener {
        fun searching(isSearching: Boolean)
        fun onDevicesUpdated(devices: List<AntDevice>, selectedDevices: Map<BleServiceType, List<Int>>)
    }

    private val listeners = ArrayList<MainServiceListener>()

    private val bridge = AntToBleBridge()
    val isSearching: Boolean
        get() = bridge.isSearching

    override fun onCreate() {
        super.onCreate()
        startServiceInForeground()
        bridge.startup(this) {
            val newDevices = bridge.antDevices.values.toList()
            listeners.forEach {
                it.onDevicesUpdated(newDevices, bridge.selectedDevices)
            }
        }
        listeners.forEach { it.searching(true) }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let {
            if (it == STOP_SELF_ACTION) {
                stopSearching()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private val binder: IBinder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    /**
     * Get the services for communicating with it
     */
    inner class LocalBinder : Binder() {
        val service: MainService
            get() = this@MainService
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(channelId: String, channelName: String) {
        val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
        channel.lightColor = Color.BLUE
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun startServiceInForeground() {
        val intent = Intent(this, MainService::class.java)
        intent.action = STOP_SELF_ACTION
        val stopPendingIntent = PendingIntent.getService(this, 0, intent, 0)
        val stopAction = NotificationCompat.Action(R.drawable.ic_baseline_stop_24, "Stop", stopPendingIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(CHANNEL_DEFAULT_IMPORTANCE, MAIN_CHANNEL_NAME)

            // Create the PendingIntent
            val notifyPendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this.applicationContext, LaunchActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT)

            // build a notification
            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_DEFAULT_IMPORTANCE)
                    .setContentTitle(getText(R.string.app_name))
                    .setContentText("Active")
                    .setSmallIcon(R.drawable.ic_baseline_bluetooth_24)
                    .setAutoCancel(false)
                    .setContentIntent(notifyPendingIntent)
                    .addAction(stopAction)
                    .setTicker(getText(R.string.app_name))
                    .build()
            startForeground(ONGOING_NOTIFICATION_ID, notification)
        } else {
            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_DEFAULT_IMPORTANCE)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText("Active")
                    .setSmallIcon(R.drawable.ic_baseline_bluetooth_24)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(false)
                    .addAction(stopAction)
                    .build()
            startForeground(ONGOING_NOTIFICATION_ID, notification)
        }
    }

    private fun cleanup() {
        listeners.forEach { it.onDevicesUpdated(emptyList(), emptyMap()) }
        listeners.forEach { it.searching(false) }
    }

    fun stopSearching() {
        cleanup()
        bridge.stop()
        stopSelf()
    }

    fun addListener(serviceListener: MainServiceListener) {
        listeners.add(serviceListener)
    }

    fun removeListener(serviceListener: MainServiceListener) {
        listeners.remove(serviceListener)
    }

    fun getConnectedDevices(): HashMap<Int, AntDevice> {
        return bridge.antDevices
    }

    fun deviceSelected(antDevice: AntDevice) {
        bridge.deviceSelected(antDevice)
    }

    override fun onDestroy() {
        stopSearching()
        super.onDestroy()
    }
}