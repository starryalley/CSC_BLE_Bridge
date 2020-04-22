package idv.markkuo.cscblebridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.Nullable;

import com.dsi.ant.plugins.antplus.pcc.AntPlusBikeCadencePcc;
import com.dsi.ant.plugins.antplus.pcc.AntPlusBikeSpeedDistancePcc;
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceState;
import com.dsi.ant.plugins.antplus.pcc.defines.EventFlag;
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult;
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc;
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

public class CSCService extends Service {
    private static final String TAG = CSCService.class.getSimpleName();
    private static final int ONGOING_NOTIFICATION_ID = 9999;
    private static final String CHANNEL_DEFAULT_IMPORTANCE = "csc_ble_channel";

    // Ant+ sensors
    AntPlusBikeSpeedDistancePcc bsdPcc = null;
    PccReleaseHandle<AntPlusBikeSpeedDistancePcc> bsdReleaseHandle = null;
    AntPlusBikeCadencePcc bcPcc = null;
    PccReleaseHandle<AntPlusBikeCadencePcc> bcReleaseHandle = null;

    // 700x23c circumference in meter
    private static final BigDecimal circumference = new BigDecimal(2.095);
    // m/s to km/h ratio
    private static final BigDecimal msToKmSRatio = new BigDecimal(3.6);

    // bluetooth API
    private BluetoothManager mBluetoothManager;
    private BluetoothGattServer mBluetoothGattServer;
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;
    // notification subscribers
    private Set<BluetoothDevice> mRegisteredDevices = new HashSet<>();

    // last wheel and crank (speed/cadence) information to send to CSCProfile
    private long cumulativeWheelRevolution = 0;
    private long cumulativeCrankRevolution = 0;
    private int lastWheelEventTime = 0;
    private int lastCrankEventTime = 0;

    private long lastSpeedTimestamp = 0;
    private long lastCadenceTimestamp = 0;
    private float lastSpeed = 0;
    private int lastCadence = 0;

    private AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeSpeedDistancePcc> mBSDResultReceiver = new AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeSpeedDistancePcc>() {
        @Override
        public void onResultReceived(AntPlusBikeSpeedDistancePcc result,
                                     RequestAccessResult resultCode, DeviceState initialDeviceState) {
            switch (resultCode) {
                case SUCCESS:
                    bsdPcc = result;
                    Log.d(TAG, result.getDeviceName() + ": " + initialDeviceState);
                    // send broadcast
                    Intent i = new Intent("idv.markkuo.cscblebridge.ANTDATA");
                    i.putExtra("bsd_service_status", initialDeviceState.toString());
                    sendBroadcast(i);

                    subscribeToEvents();
                    break;
                default:
                    Log.e(TAG,  "BSD error:" + initialDeviceState + ", resultCode" + resultCode);
            }
        }

        private void subscribeToEvents() {
            bsdPcc.subscribeCalculatedSpeedEvent(new AntPlusBikeSpeedDistancePcc.CalculatedSpeedReceiver(circumference) {
                @Override
                public void onNewCalculatedSpeed(final long estTimestamp,
                                                 final EnumSet<EventFlag> eventFlags, final BigDecimal calculatedSpeed) {
                    // convert m/s to km/h
                    float speed = calculatedSpeed.multiply(msToKmSRatio).floatValue();
                    //Log.v(TAG, "Speed:" + speed);
                    lastSpeed = speed;
                }
            });

            bsdPcc.subscribeRawSpeedAndDistanceDataEvent(new AntPlusBikeSpeedDistancePcc.IRawSpeedAndDistanceDataReceiver() {
                @Override
                public void onNewRawSpeedAndDistanceData(long estTimestamp, EnumSet<EventFlag> eventFlags, BigDecimal timestampOfLastEvent, long cumulativeRevolutions) {
                    //estTimestamp - The estimated timestamp of when this event was triggered. Useful for correlating multiple events and determining when data was sent for more accurate data records.
                    //eventFlags - Informational flags about the event.
                    //timestampOfLastEvent - Sensor reported time counter value of last distance or speed computation (up to 1/200s accuracy). Units: s. Rollover: Every ~46 quadrillion s (~1.5 billion years).
                    //cumulativeRevolutions - Total number of revolutions since the sensor was first connected. Note: If the subscriber is not the first PCC connected to the device the accumulation will probably already be at a value greater than 0 and the subscriber should save the first received value as a relative zero for itself. Units: revolutions. Rollover: Every ~9 quintillion revolutions.
                    Log.v(TAG, "=> BSD: Cumulative revolution:" + cumulativeRevolutions + ", lastEventTime:" + timestampOfLastEvent);
                    cumulativeWheelRevolution = cumulativeRevolutions;
                    lastWheelEventTime = (int)(timestampOfLastEvent.doubleValue()*1024.0);
                    lastSpeedTimestamp = estTimestamp;
                }
            });

        }
    };

    private AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeCadencePcc> mBCResultReceiver = new AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeCadencePcc>() {
        // Handle the result, connecting to events on success or reporting
        // failure to user.
        @Override
        public void onResultReceived(AntPlusBikeCadencePcc result,
                                     RequestAccessResult resultCode, DeviceState initialDeviceState) {
            switch (resultCode) {
                case SUCCESS:
                    bcPcc = result;
                    Log.d(TAG, result.getDeviceName() + ": " + initialDeviceState);
                    // send broadcast
                    Intent i = new Intent("idv.markkuo.cscblebridge.ANTDATA");
                    i.putExtra("bc_service_status", initialDeviceState.toString());
                    sendBroadcast(i);

                    subscribeToEvents();
                    break;

                default:
                    Log.e(TAG,  "BC error:" + initialDeviceState + ", resultCode" + resultCode);
            }
        }

        private void subscribeToEvents() {
            bcPcc.subscribeCalculatedCadenceEvent(new AntPlusBikeCadencePcc.ICalculatedCadenceReceiver() {
                @Override
                public void onNewCalculatedCadence(final long estTimestamp,
                                                   final EnumSet<EventFlag> eventFlags, final BigDecimal calculatedCadence) {

                    //Log.v(TAG, "Cadence:" + calculatedCadence.intValue());
                    lastCadence = calculatedCadence.intValue();
                }
            });

            bcPcc.subscribeRawCadenceDataEvent(new AntPlusBikeCadencePcc.IRawCadenceDataReceiver() {
                @Override
                public void onNewRawCadenceData(final long estTimestamp,
                                                final EnumSet<EventFlag> eventFlags, final BigDecimal timestampOfLastEvent,
                                                final long cumulativeRevolutions) {
                    Log.v(TAG, "=> BC: Cumulative revolution:" + cumulativeRevolutions + ", lastEventTime:" + timestampOfLastEvent);
                    cumulativeCrankRevolution = cumulativeRevolutions;
                    lastCrankEventTime = (int)(timestampOfLastEvent.doubleValue()*1024.0);
                    lastCadenceTimestamp = estTimestamp;
                }
            });
        }
    };

    enum AntSensorType {
        CyclingSpeed,
        CyclingCadence
    }
    class AntDeviceChangeReceiver implements AntPluginPcc.IDeviceStateChangeReceiver {
        private AntSensorType type;
        public AntDeviceChangeReceiver(AntSensorType type) {
            this.type = type;
        }
        @Override
        public void onDeviceStateChange(final DeviceState newDeviceState) {
            String extraName = "unknown";
            if (type == AntSensorType.CyclingSpeed) {
                extraName = "bsd_service_status";
                Log.d(TAG, "Speed sensor onDeviceStateChange:" + newDeviceState);
            } else if (type == AntSensorType.CyclingCadence) {
                extraName = "bc_service_status";
                Log.d(TAG, "Cadence sensor onDeviceStateChange:" + newDeviceState);
            }
            // send broadcast
            Intent i = new Intent("idv.markkuo.cscblebridge.ANTDATA");
            i.putExtra(extraName, newDeviceState.name());
            sendBroadcast(i);

            // if the device is dead (closed)
            if (newDeviceState == DeviceState.DEAD) {
                bsdPcc = null;

            }
        }
    };

    // Receives state changes and shows it on the status display line
    private AntPluginPcc.IDeviceStateChangeReceiver mBSDDeviceStateChangeReceiver = new AntDeviceChangeReceiver(AntSensorType.CyclingSpeed);
    private AntPluginPcc.IDeviceStateChangeReceiver mBCDeviceStateChangeReceiver = new AntDeviceChangeReceiver(AntSensorType.CyclingCadence);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        //TODO do something useful
        Log.d(TAG, "Service onStartCommand");
        return Service.START_STICKY;
    }

    private boolean checkBluetoothSupport(BluetoothAdapter bluetoothAdapter) {
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Bluetooth is not supported");
            return false;
        }
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.w(TAG, "Bluetooth LE is not supported");
            return false;
        }

        return true;
    }

    private BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);

            switch (state) {
                case BluetoothAdapter.STATE_ON:
                    startAdvertising();
                    startServer();
                    break;
                case BluetoothAdapter.STATE_OFF:
                    stopServer();
                    stopAdvertising();
                    break;
                default:
                    // Do nothing
            }

        }
    };

    @Override
    public void onCreate() {
        Log.d(TAG, "Service started");
        super.onCreate();

        initAntPlus();


        // bluetooth
        mBluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();
        // We can't continue without proper Bluetooth support
        if (!checkBluetoothSupport(bluetoothAdapter)) {
            return;
        }

        // Register for system Bluetooth events
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mBluetoothReceiver, filter);
        if (!bluetoothAdapter.isEnabled()) {
            Log.d(TAG, "Bluetooth is currently disabled...enabling");
            bluetoothAdapter.enable();
        } else {
            Log.d(TAG, "Bluetooth enabled...starting services");
            startAdvertising();
            startServer();
        }

        // for foreground service

        Intent notificationIntent = new Intent(this, CSCService.class);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, notificationIntent, 0);

        // create notification channel
        int importance = NotificationManager.IMPORTANCE_DEFAULT;
        NotificationChannel channel = new NotificationChannel(CHANNEL_DEFAULT_IMPORTANCE, CHANNEL_DEFAULT_IMPORTANCE, importance);
        channel.setDescription(CHANNEL_DEFAULT_IMPORTANCE);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        assert notificationManager != null;
        notificationManager.createNotificationChannel(channel);
        // build a notification
        Notification notification =
                new Notification.Builder(this, CHANNEL_DEFAULT_IMPORTANCE)
                        .setContentTitle(getText(R.string.app_name))
                        .setContentText("Active")
                        //.setSmallIcon(R.drawable.icon)
                        .setContentIntent(pendingIntent)
                        .setTicker(getText(R.string.app_name))
                        .build();
        // start this service as a foreground one
        startForeground(ONGOING_NOTIFICATION_ID, notification);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "onTaskRemoved called");
        super.onTaskRemoved(rootIntent);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");
        super.onDestroy();
        if(bsdReleaseHandle != null)
            bsdReleaseHandle.close();

        if (bcReleaseHandle != null)
            bcReleaseHandle.close();

        BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();
        if (bluetoothAdapter.isEnabled()) {
            stopServer();
            stopAdvertising();
        }

        unregisterReceiver(mBluetoothReceiver);
    }


    /**
     * Begin advertising over Bluetooth that this device is connectable
     * and supports the Current Time Service.
     */
    private void startAdvertising() {
        BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();
        Log.i(TAG, "isLe2MPhySupported:" + bluetoothAdapter.isLe2MPhySupported() + ",isMultipleAdvertisementSupported:" + bluetoothAdapter.isMultipleAdvertisementSupported() +
                ",isLeCodedPhySupported:" + bluetoothAdapter.isLeCodedPhySupported() + ",isLeExtendedAdvertisingSupported:" + bluetoothAdapter.isLeExtendedAdvertisingSupported() +
                ",isLePeriodicAdvertisingSupported:" + bluetoothAdapter.isLePeriodicAdvertisingSupported() + ",address:" + bluetoothAdapter.getAddress());
        mBluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (mBluetoothLeAdvertiser == null) {
            Log.w(TAG, "Failed to create advertiser");
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .setIncludeTxPowerLevel(false)
                //.addServiceData()
                .addServiceUuid(new ParcelUuid(CSCProfile.CSC_SERVICE))
                .build();

//        AdvertiseData scanResponseData = new AdvertiseData.Builder()
//                .addServiceUuid(new ParcelUuid(CSCProfile.CSC_SERVICE))
//                .setIncludeTxPowerLevel(false)
//                .build();

        mBluetoothLeAdvertiser
                .startAdvertising(settings, data, mAdvertiseCallback);
                //.startAdvertising(settings, data, scanResponseData, mAdvertiseCallback);
    }

    /**
     * Stop Bluetooth advertisements.
     */
    private void stopAdvertising() {
        if (mBluetoothLeAdvertiser == null) return;

        mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
    }

    /**
     * Initialize the GATT server instance with the services/characteristics
     * from the Time Profile.
     */
    private void startServer() {
        mBluetoothGattServer = mBluetoothManager.openGattServer(this, mGattServerCallback);
        if (mBluetoothGattServer == null) {
            Log.w(TAG, "Unable to create GATT server");
            return;
        }

        // TODO: enable either 1 of them or both of them according to user selection
        CSCProfile.setFeature((byte)(CSCProfile.CSC_FEATURE_WHEEL_REV | CSCProfile.CSC_FEATURE_CRANK_REV));
        mBluetoothGattServer.addService(CSCProfile.createCSCService());
        Log.d(TAG, "CSCP enabled!");
    }

    /**
     * Shut down the GATT server.
     */
    private void stopServer() {
        if (mBluetoothGattServer == null) return;

        mBluetoothGattServer.close();
    }

    /**
     * Callback to receive information about the advertisement process.
     */
    private AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {

        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "LE Advertise Started:" + settingsInEffect);
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.w(TAG, "LE Advertise Failed: "+errorCode);
        }
    };

    Handler handler = new Handler();
    private Runnable periodicUpdate = new Runnable () {
        @Override
        public void run() {
            // scheduled next run in 1 sec
            handler.postDelayed(periodicUpdate, 1000);

            notifyRegisteredDevices();
            // update UI by sending broadcast to our main activity
            Intent i = new Intent("idv.markkuo.cscblebridge.ANTDATA");
            i.putExtra("speed", lastSpeed);
            i.putExtra("cadence", lastCadence);
            i.putExtra("speed_timestamp", lastSpeedTimestamp);
            i.putExtra("cadence_timestamp", lastCadenceTimestamp);
            Log.d(TAG, "Updating UI: speed:" + lastSpeed + ", cadence:" + lastCadence + ", speed_ts:" + lastSpeedTimestamp + ", cadence_ts:" + lastCadenceTimestamp);
            sendBroadcast(i);
        }
    };

    /**
     * Send a CSC service notification to any devices that are subscribed
     * to the characteristic.
     */
    private void notifyRegisteredDevices() {
        if (mRegisteredDevices.isEmpty()) {
            Log.v(TAG, "No subscribers registered");
            return;
        }

        byte[] data = CSCProfile.getMeasurement(cumulativeWheelRevolution, lastWheelEventTime,
                                                cumulativeCrankRevolution, lastCrankEventTime);

        Log.v(TAG, "Sending update to " + mRegisteredDevices.size() + " subscribers");
        for (BluetoothDevice device : mRegisteredDevices) {
            BluetoothGattCharacteristic measurementCharacteristic = mBluetoothGattServer
                    .getService(CSCProfile.CSC_SERVICE)
                    .getCharacteristic(CSCProfile.CSC_MEASUREMENT);
            if (!measurementCharacteristic.setValue(data)) {
                Log.w(TAG, "CSC Measurement data isn't set properly!");
            }
            // false is used to send a notification
            mBluetoothGattServer.notifyCharacteristicChanged(device, measurementCharacteristic, false);
        }
    }

    /**
     * Callback to handle incoming requests to the GATT server.
     * All read/write requests for characteristics and descriptors are handled here.
     */
    private BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {

        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            Log.i(TAG, "onServiceAdded(): status:" + status + ", service:" + service);
            //startAdvertising();//TODO: check if this is correct
            // start sending notification to subscribed device
            handler.post(periodicUpdate);
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            Log.i(TAG, "onConnectionStateChange() status:" + status + "->" + newState + ", device" + device);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BluetoothDevice CONNECTED: " + device);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "BluetoothDevice DISCONNECTED: " + device);
                //Remove device from any active subscriptions
                mRegisteredDevices.remove(device);
            }
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            Log.v(TAG, "onNotificationSent() result:" + status);
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            Log.d(TAG, "onMtuChanged:" + device + " =>" + mtu);
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            long now = System.currentTimeMillis();
            if (CSCProfile.CSC_MEASUREMENT.equals(characteristic.getUuid())) {
                Log.i(TAG, "Read CSC Measurement");
                //TODO: this should never happen since this characteristic doesn't support read
                mBluetoothGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        null);
            } else if (CSCProfile.CSC_FEATURE.equals(characteristic.getUuid())) {
                Log.i(TAG, "Read CSC Feature");
                mBluetoothGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        CSCProfile.getFeature());
            } else {
                // Invalid characteristic
                Log.w(TAG, "Invalid Characteristic Read: " + characteristic.getUuid());
                mBluetoothGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0,
                        null);
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattDescriptor descriptor) {
            if (CSCProfile.CLIENT_CONFIG.equals(descriptor.getUuid())) {
                Log.d(TAG, "Config descriptor read");
                byte[] returnValue;
                if (mRegisteredDevices.contains(device)) {
                    returnValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                } else {
                    returnValue = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                }
                mBluetoothGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        returnValue);
            } else {
                Log.w(TAG, "Unknown descriptor read request");
                mBluetoothGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        null);
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            if (CSCProfile.CLIENT_CONFIG.equals(descriptor.getUuid())) {
                if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                    Log.d(TAG, "Subscribe device to notifications: " + device);
                    mRegisteredDevices.add(device);
                } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
                    Log.d(TAG, "Unsubscribe device from notifications: " + device);
                    mRegisteredDevices.remove(device);
                }

                if (responseNeeded) {
                    mBluetoothGattServer.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            null);
                }
            } else {
                Log.w(TAG, "Unknown descriptor write request");
                if (responseNeeded) {
                    mBluetoothGattServer.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            0,
                            null);
                }
            }
        }
    };

    private void initAntPlus() {
        //Release the old access if it exists
        if(bsdReleaseHandle != null)
            bsdReleaseHandle.close();

        Log.d(TAG, "requesting ANT+ access");
        // starts speed sensor search
        bsdReleaseHandle = AntPlusBikeSpeedDistancePcc.requestAccess(this, 0, 0, false,
                mBSDResultReceiver, mBSDDeviceStateChangeReceiver);
        // starts cadence sensor search
        bcReleaseHandle = AntPlusBikeCadencePcc.requestAccess(this, 0, 0, false,
                mBCResultReceiver, mBCDeviceStateChangeReceiver);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}
