package idv.markkuo.cscblebridge.service.ble

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import idv.markkuo.cscblebridge.service.ant.AntDevice
import java.util.*
import java.util.concurrent.Semaphore
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class BleServer {
    companion object {
        private const val TAG = "BleServer"
        private const val UPDATE_INTERVAL_MS = 1000L
    }
    private var bluetoothManager: BluetoothManager? = null
    private var context: Context? = null
    private var server: BluetoothGattServer? = null
    private var timer: Timer? = null
    private val registeredDevices: HashSet<BluetoothDevice> = HashSet()
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private val antData = hashMapOf<BleServiceType, ArrayList<AntDevice>>()
    private val servicesToCreate = ArrayList(BleServiceType.serviceTypes)
    private val mutex = Semaphore(1)
    var selectedDevices = HashMap<BleServiceType, ArrayList<Int>>()

    fun startServer(context: Context) {
        this.context = context
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        val bluetoothAdapter: BluetoothAdapter = bluetoothManager?.adapter
                ?: throw UnsupportedOperationException("Bluetooth adapter is not supported")
        if (!checkBluetoothSupport(bluetoothAdapter, context.packageManager)) {
            throw UnsupportedOperationException("Bluetooth LE isn't supported")
        }

        // Register for system Bluetooth events
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(bluetoothReceiver, filter)

        if (!bluetoothAdapter.isEnabled) {
            Log.d(TAG, "Bluetooth is currently disabled...enabling")
            bluetoothAdapter.enable()
        } else {
            Log.d(TAG, "Bluetooth enabled...starting services")
            startAdvertising()
            startupInternal(context)
        }
    }

    fun updateData(serviceType: BleServiceType, antDevice: AntDevice) {
        antData[serviceType]?.let { devices ->
            val alreadyExists: AntDevice? = devices.firstOrNull { it.deviceId == antDevice.deviceId }

            if (alreadyExists != null) {
                devices.remove(alreadyExists)
            }
            devices.add(antDevice)

            return@updateData
        }

        antData[serviceType] = arrayListOf(antDevice)
    }

    private fun startupInternal(context: Context) {
        bluetoothManager = bluetoothManager ?: context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        server = bluetoothManager?.openGattServer(context, gattServerCallback)
                ?: throw UnsupportedOperationException("Bluetooth manager could not be created")


        createNextService()

        timer?.cancel()
        timer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    notifyRegisteredDevices()
                }
            }, UPDATE_INTERVAL_MS, UPDATE_INTERVAL_MS)
        }
    }

    private fun createNextService() {
        mutex.acquire()
        if (servicesToCreate.isNotEmpty()) {
            createService(servicesToCreate[0])
            servicesToCreate.removeFirst()
        }
        mutex.release()
    }

    fun notifyRegisteredDevices() {
        Log.i(TAG, "Notifying registered devices ${registeredDevices.size}")
        registeredDevices.forEach { device ->
            Log.i(TAG, "Notifying ${device.name}")
            BleServiceType.serviceTypes.forEach { bleService ->
                val service = server?.getService(bleService.serviceId)
                if (service != null) {
                    Log.i(TAG, "Notifying ${device.name} for ${service.uuid}")
                    val antDevices = antData[bleService]
                    val selectedAntDevices = antDevices?.filter { selectedDevices[bleService]?.contains(it.deviceId) ?: false }
                    if (selectedAntDevices != null && selectedAntDevices.isNotEmpty()) {
                        val data = bleService.getBleData(selectedAntDevices)
                        val measurementCharacteristic: BluetoothGattCharacteristic = service
                                .getCharacteristic(bleService.measurement)
                        if (!measurementCharacteristic.setValue(data)) {
                            Log.w(TAG, "${bleService.measurement} Measurement data isn't set properly!")
                        }
                        // false is used to send a notification
                        server?.notifyCharacteristicChanged(device, measurementCharacteristic, false)
                    } else {
                        Log.i(TAG, "Not notifying anything, ant device was null")
                    }
                } else {
                    Log.v(TAG, "Service ${bleService.serviceId} was not found as an installed service")
                }
            }

        }
    }

    fun stopServer() {
        this.context?.unregisterReceiver(bluetoothReceiver)
        server?.close()
        timer?.cancel()
        timer = null

        context = null
    }

    /**
     * Begin advertising over Bluetooth that this device is connectable
     */
    private fun startAdvertising() {
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Failed to create bluetooth adapter")
            return
        }
        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
        bluetoothLeAdvertiser?.let { it ->
            val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true)
                    .setTimeout(0)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .build()
            val dataBuilder = AdvertiseData.Builder().setIncludeDeviceName(true).setIncludeTxPowerLevel(true)
            BleServiceType.serviceTypes.forEach { objectInstance ->
                dataBuilder.addServiceUuid(ParcelUuid(objectInstance.serviceId))
            }
            it.startAdvertising(settings, dataBuilder.build(), mAdvertiseCallback)
        } ?: Log.w(TAG, "Failed to create advertiser")
    }

    /**
     * Callback to receive information about the advertisement process
     */
    private val mAdvertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "LE Advertise Started: $settingsInEffect")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "LE Advertise Failed: $errorCode")
        }
    }

    /**
     * Stop Bluetooth advertisements
     */
    private fun stopAdvertising() {
        if (bluetoothLeAdvertiser == null) return
        bluetoothLeAdvertiser?.stopAdvertising(mAdvertiseCallback)
    }

    private fun checkBluetoothSupport(bluetoothAdapter: BluetoothAdapter?, packageManager: PackageManager): Boolean {
        return !(bluetoothAdapter == null || !packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
    }

    fun createService(type: BleServiceType) {
        BluetoothGattService(type.serviceId, BluetoothGattService.SERVICE_TYPE_PRIMARY).also {
            val measurement = BluetoothGattCharacteristic(type.measurement,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ)
            val configDescriptor = BluetoothGattDescriptor(BleServiceType.CLIENT_CONFIG,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
            measurement.addDescriptor(configDescriptor)
            it.addCharacteristic(measurement)

            if (type.feature != null) {
                val feature = BluetoothGattCharacteristic(type.feature,
                        BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_READ)
                it.addCharacteristic(feature)
            }
            server?.addService(it) ?: throw IllegalStateException("Server must be started before adding a service")
        }
    }

    private val bluetoothReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)) {
                BluetoothAdapter.STATE_ON -> {
                    startAdvertising()
                    startupInternal(context)
                }
                BluetoothAdapter.STATE_OFF -> {
                    stopServer()
                    stopAdvertising()
                }
            }
        }
    }

    private val gattServerCallback: BluetoothGattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.i(TAG, "onServiceAdded(): status:$status, service:$service")
            createNextService()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (registeredDevices.contains(device)) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "BluetoothDevice DISCONNECTED: " + device.name + " [" + device.address + "]")
                    //Remove device from any active subscriptions
                    registeredDevices.remove(device)
                } else {
                    Log.i(TAG, "onConnectionStateChange() status:$status->$newState, device$device")
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            Log.v(TAG, "onNotificationSent() result:$status")
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.d(TAG, "onMtuChanged:$device =>$mtu")
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                                 characteristic: BluetoothGattCharacteristic) {

            val uuid = characteristic.uuid
            val feature = BleServiceType.serviceTypes.firstOrNull {
                it.feature == uuid
            }
            val measurement = BleServiceType.serviceTypes.firstOrNull {
                it.measurement == uuid
            }

            if (feature != null || measurement != null) {
                server!!.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        feature?.getSupportedFeatures())
            } else {
                server!!.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0,
                        null)
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                             descriptor: BluetoothGattDescriptor) {
            if (BleServiceType.CLIENT_CONFIG == descriptor.uuid) {
                Log.d(TAG, "Config descriptor read")
                val returnValue: ByteArray = if (registeredDevices.contains(device)) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                server!!.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        returnValue)
            } else {
                Log.w(TAG, "Unknown descriptor read request")
                server!!.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        null)
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int,
                                              descriptor: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean,
                                              offset: Int, value: ByteArray) {
            if (BleServiceType.CLIENT_CONFIG == descriptor.uuid) {
                if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                    Log.d(TAG, "Subscribe device to notifications: $device")
                    registeredDevices.add(device)
                } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
                    Log.d(TAG, "Unsubscribe device from notifications: $device")
                    registeredDevices.remove(device)
                }
                if (responseNeeded) {
                    server!!.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            null)
                }
            } else {
                Log.w(TAG, "Unknown descriptor write request")
                if (responseNeeded) {
                    server!!.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            0,
                            null)
                }
            }
        }
    }
}