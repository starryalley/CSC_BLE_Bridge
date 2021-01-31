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
import android.graphics.Color;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.dsi.ant.plugins.antplus.pcc.AntPlusBikeCadencePcc;
import com.dsi.ant.plugins.antplus.pcc.AntPlusBikeSpeedDistancePcc;
import com.dsi.ant.plugins.antplus.pcc.AntPlusHeartRatePcc;
import com.dsi.ant.plugins.antplus.pcc.AntPlusStrideSdmPcc;
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceState;
import com.dsi.ant.plugins.antplus.pcc.defines.EventFlag;
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult;
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc;
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;

public class CSCService extends Service {
    private static final String TAG = CSCService.class.getSimpleName();
    private static final int ONGOING_NOTIFICATION_ID = 9999;
    private static final String CHANNEL_DEFAULT_IMPORTANCE = "csc_ble_channel";
    private static final String MAIN_CHANNEL_NAME = "CscService";

    // Ant+ sensors
    private AntPlusBikeSpeedDistancePcc bsdPcc = null;
    private PccReleaseHandle<AntPlusBikeSpeedDistancePcc> bsdReleaseHandle = null;
    private AntPlusBikeCadencePcc bcPcc = null;
    private PccReleaseHandle<AntPlusBikeCadencePcc> bcReleaseHandle = null;
    private AntPlusHeartRatePcc hrPcc = null;
    private PccReleaseHandle<AntPlusHeartRatePcc> hrReleaseHandle = null;
    private AntPlusStrideSdmPcc ssPcc = null;
    private PccReleaseHandle<AntPlusStrideSdmPcc> ssReleaseHandle = null;

    // Checks that the callback that is done after a BluetoothGattServer.addService() has been complete.
    // More services cannot be added until the callback has completed successfully
    private boolean btServiceInitialized = false;


    // 700x23c circumference in meter
    private static final BigDecimal circumference = new BigDecimal("2.095");
    // m/s to km/h ratio
    private static final BigDecimal msToKmSRatio = new BigDecimal("3.6");

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

    // for UI updates
    private long lastSpeedTimestamp = 0;
    private long lastCadenceTimestamp = 0;
    private long lastHRTimestamp = 0;
    private long lastSSDistanceTimestamp = 0;
    private long lastSSSpeedTimestamp = 0;
    private long lastSSStrideCountTimestamp = 0;
    private float lastSpeed = 0;
    private int lastCadence = 0;
    private int lastHR = 0;
    private long lastSSDistance = 0;
    private float lastSSSpeed = 0;
    private long lastStridePerMinute = 0;

    // for onCreate() failure case
    private boolean initialised = false;

    // Used to flag if we have a combined speed and cadence sensor and have already re-connected as combined
    private boolean combinedSensorConnected = false;

    // Binder for activities wishing to communicate with this service
    private final IBinder binder = new LocalBinder();

    private AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeSpeedDistancePcc> mBSDResultReceiver = new AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeSpeedDistancePcc>() {

        @Override
        public void onResultReceived(AntPlusBikeSpeedDistancePcc result,
                                     RequestAccessResult resultCode, DeviceState initialDeviceState) {
            if (resultCode == RequestAccessResult.SUCCESS) {
                bsdPcc = result;
                Log.d(TAG, result.getDeviceName() + ": " + initialDeviceState);
                subscribeToEvents();
            } else if (resultCode == RequestAccessResult.USER_CANCELLED) {
                Log.d(TAG, "BSD Closed:" + resultCode);
            } else {
                Log.w(TAG, "BSD state changed:" + initialDeviceState + ", resultCode:" + resultCode);
            }
            // send broadcast
            Intent i = new Intent("idv.markkuo.cscblebridge.ANTDATA");
            i.putExtra("bsd_service_status", initialDeviceState.toString() + "\n(" + resultCode + ")");
            sendBroadcast(i);
        }

        private void subscribeToEvents() {
            bsdPcc.subscribeCalculatedSpeedEvent(new AntPlusBikeSpeedDistancePcc.CalculatedSpeedReceiver(circumference) {
                @Override
                public void onNewCalculatedSpeed(final long estTimestamp,
                                                 final EnumSet<EventFlag> eventFlags, final BigDecimal calculatedSpeed) {
                    // convert m/s to km/h
                    lastSpeed = calculatedSpeed.multiply(msToKmSRatio).floatValue();
                    //Log.v(TAG, "Speed:" + lastSpeed);
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

            if (bsdPcc.isSpeedAndCadenceCombinedSensor() && !combinedSensorConnected) {
                // reconnect cadence sensor as combined sensor
                if (bcReleaseHandle != null) {
                    bcReleaseHandle.close();
                }
                combinedSensorConnected = true;
                bcReleaseHandle = AntPlusBikeCadencePcc.requestAccess(getApplicationContext(), bsdPcc.getAntDeviceNumber(), 0, true,
                        mBCResultReceiver, mBCDeviceStateChangeReceiver);
            }

        }
    };

    private AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeCadencePcc> mBCResultReceiver = new AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeCadencePcc>() {
        // Handle the result, connecting to events on success or reporting
        // failure to user.
        @Override
        public void onResultReceived(AntPlusBikeCadencePcc result,
                                     RequestAccessResult resultCode, DeviceState initialDeviceState) {
            if (resultCode == RequestAccessResult.SUCCESS) {
                bcPcc = result;
                Log.d(TAG, result.getDeviceName() + ": " + initialDeviceState);
                subscribeToEvents();
            } else if (resultCode == RequestAccessResult.USER_CANCELLED) {
                Log.d(TAG, "BC Closed:" + resultCode);
            } else {
                Log.w(TAG, "BC state changed:" + initialDeviceState + ", resultCode:" + resultCode);
            }
            // send broadcast
            Intent i = new Intent("idv.markkuo.cscblebridge.ANTDATA");
            i.putExtra("bc_service_status", initialDeviceState.toString() + "\n(" + resultCode + ")");
            sendBroadcast(i);
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

            if (bcPcc.isSpeedAndCadenceCombinedSensor() && !combinedSensorConnected) {
                // reconnect speed sensor as a combined sensor
                if (bsdReleaseHandle != null) {
                    bsdReleaseHandle.close();
                }
                combinedSensorConnected = true;
                bsdReleaseHandle = AntPlusBikeSpeedDistancePcc.requestAccess(getApplicationContext(), bcPcc.getAntDeviceNumber(), 0, true,
                        mBSDResultReceiver, mBSDDeviceStateChangeReceiver);
            }
        }
    };

    private AntPluginPcc.IPluginAccessResultReceiver<AntPlusHeartRatePcc> mHRResultReceiver = new AntPluginPcc.IPluginAccessResultReceiver<AntPlusHeartRatePcc>() {
        @Override
        public void onResultReceived(AntPlusHeartRatePcc result, RequestAccessResult resultCode, DeviceState initialDeviceState) {
            if (resultCode == RequestAccessResult.SUCCESS) {
                hrPcc = result;
                Log.d(TAG, result.getDeviceName() + ": " + initialDeviceState);
                subscribeToEvents();
            } else if (resultCode == RequestAccessResult.USER_CANCELLED) {
                Log.d(TAG, "HR Closed:" + resultCode);
            } else {
                Log.w(TAG, "HR state changed:" + initialDeviceState + ", resultCode:" + resultCode);
            }
            // send broadcast
            Intent i = new Intent("idv.markkuo.cscblebridge.ANTDATA");
            i.putExtra("hr_service_status", initialDeviceState.toString() + "\n(" + resultCode + ")");
            sendBroadcast(i);
        }

        private void subscribeToEvents() {
            hrPcc.subscribeHeartRateDataEvent(new AntPlusHeartRatePcc.IHeartRateDataReceiver() {
                @Override
                public void onNewHeartRateData(final long estTimestamp, EnumSet<EventFlag> eventFlags,
                                               final int computedHeartRate, final long heartBeatCount,
                                               final BigDecimal heartBeatEventTime, final AntPlusHeartRatePcc.DataState dataState) {
                    lastHR = computedHeartRate;
                    lastHRTimestamp = estTimestamp;
                }
            });
        }
    };

    private AntPluginPcc.IPluginAccessResultReceiver<AntPlusStrideSdmPcc> mSSResultReceiver = new AntPluginPcc.IPluginAccessResultReceiver<AntPlusStrideSdmPcc>() {
        @Override
        public void onResultReceived(AntPlusStrideSdmPcc result, RequestAccessResult resultCode, DeviceState initialDeviceState) {
            if (resultCode == RequestAccessResult.SUCCESS) {
                ssPcc = result;
                Log.d(TAG, result.getDeviceName() + ": " + initialDeviceState);
                subscribeToEvents();
            } else if (resultCode == RequestAccessResult.USER_CANCELLED) {
                Log.d(TAG, "SS Closed:" + resultCode);
            } else {
                Log.w(TAG, "SS state changed: " + initialDeviceState + ", resultCode:" + resultCode);
            }

            // send broadcast
            Intent i = new Intent("idv.markkuo.cscblebridge.ANTDATA");
            i.putExtra("ss_service_status", initialDeviceState.toString() + "\n(" + resultCode + ")");
            sendBroadcast(i);
        }

        private void subscribeToEvents() {
            // https://www.thisisant.com/developer/ant-plus/device-profiles#528_tab
            ssPcc.subscribeStrideCountEvent(new AntPlusStrideSdmPcc.IStrideCountReceiver() {
                private static final int TEN_SECONDS_IN_MS = 10000;
                private static final int FALLBACK_MAX_LIST_SIZE = 500;
                private static final float ONE_MINUTE_IN_MS = 60000f;

                private final LinkedList<Pair<Long, Long>> strideList = new LinkedList<>();
                private final Semaphore lock = new Semaphore(1);
                @Override
                public void onNewStrideCount(long estTimestamp, EnumSet<EventFlag> eventFlags, final long cumulativeStrides) {
                    new Thread(() -> {
                        try {
                            lock.acquire();
                            // Calculate number of strides per minute, updates happen around every 500 ms, this number
                            // may be off by that amount but it isn't too significant
                            strideList.addFirst(new Pair<>(estTimestamp, cumulativeStrides));
                            long strideCount = 0;
                            boolean valueFound = false;
                            int i = 0;
                            for (Pair<Long, Long> p : strideList) {
                                // Cadence over the last 10 seconds
                                if (estTimestamp - p.first >= TEN_SECONDS_IN_MS) {
                                    valueFound = true;
                                    strideCount = calculateStepsPerMin(estTimestamp, cumulativeStrides, p);
                                    break;
                                } else if (i + 1 == strideList.size()) {
                                    // No value was found yet, it has not been 10 seconds. Give an early rough estimate
                                    strideCount = calculateStepsPerMin(estTimestamp, cumulativeStrides, p);
                                }

                                i++;
                            }
                            while ((valueFound && strideList.size() >= i + 1) || strideList.size() > FALLBACK_MAX_LIST_SIZE) {
                                strideList.removeLast();
                            }

                            lastSSStrideCountTimestamp = estTimestamp;
                            lastStridePerMinute = strideCount;
                            lock.release();
                        } catch (InterruptedException e) {
                            Log.e(TAG, "Unable to acquire lock to update running cadence", e);
                        }
                    }).start();
                }

                private long calculateStepsPerMin(long estTimestamp, long cumulativeStrides, Pair<Long, Long> p) {
                    float elapsedTimeMs = estTimestamp - p.first;
                    if (elapsedTimeMs == 0) {
                        return 0;
                    }
                    return (long) ((cumulativeStrides - p.second) * (ONE_MINUTE_IN_MS / elapsedTimeMs));
                }
            });

            ssPcc.subscribeDistanceEvent(new AntPlusStrideSdmPcc.IDistanceReceiver() {
                @Override
                public void onNewDistance(long estTimestamp, EnumSet<EventFlag> eventFlags, BigDecimal distance) {
                    lastSSDistanceTimestamp = estTimestamp;
                    lastSSDistance = distance.longValue();
                }
            });

            ssPcc.subscribeInstantaneousSpeedEvent(new AntPlusStrideSdmPcc.IInstantaneousSpeedReceiver() {
                @Override
                public void onNewInstantaneousSpeed(long estTimestamp, EnumSet<EventFlag> eventFlags, BigDecimal instantaneousSpeed) {
                    lastSSDistanceTimestamp = estTimestamp;
                    lastSSSpeed = instantaneousSpeed.floatValue();
                }
            });
        }
    };

    private enum AntSensorType {
        CyclingSpeed,
        CyclingCadence,
        HR,
        StrideBasedSpeedAndDistance
    }

    private class AntDeviceChangeReceiver implements AntPluginPcc.IDeviceStateChangeReceiver {
        private AntSensorType type;
        AntDeviceChangeReceiver(AntSensorType type) {
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
            } else if (type == AntSensorType.HR) {
                extraName = "hr_service_status";
                Log.d(TAG, "HR sensor onDeviceStateChange:" + newDeviceState);
            } else if (type == AntSensorType.StrideBasedSpeedAndDistance) {
                extraName = "ss_service_status";
                Log.d(TAG, "Stride based speed and distance onDeviceStateChange:" + newDeviceState);
            }
            // send broadcast about device status
            Intent i = new Intent("idv.markkuo.cscblebridge.ANTDATA");
            i.putExtra(extraName, newDeviceState.name());
            sendBroadcast(i);

            // if the device is dead (closed)
            if (newDeviceState == DeviceState.DEAD) {
                bsdPcc = null;
            }
        }
    }

    private AntPluginPcc.IDeviceStateChangeReceiver mBSDDeviceStateChangeReceiver = new AntDeviceChangeReceiver(AntSensorType.CyclingSpeed);
    private AntPluginPcc.IDeviceStateChangeReceiver mBCDeviceStateChangeReceiver = new AntDeviceChangeReceiver(AntSensorType.CyclingCadence);
    private AntPluginPcc.IDeviceStateChangeReceiver mHRDeviceStateChangeReceiver = new AntDeviceChangeReceiver(AntSensorType.HR);
    private AntPluginPcc.IDeviceStateChangeReceiver mSSDeviceStateChangeReceiver = new AntDeviceChangeReceiver(AntSensorType.StrideBasedSpeedAndDistance);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(CHANNEL_DEFAULT_IMPORTANCE, MAIN_CHANNEL_NAME);

            // Create the PendingIntent
            PendingIntent notifyPendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    new Intent(this.getApplicationContext(),MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT);

            // build a notification
            Notification notification =
                    new Notification.Builder(this, CHANNEL_DEFAULT_IMPORTANCE)
                            .setContentTitle(getText(R.string.app_name))
                            .setContentText("Active")
                            .setSmallIcon(R.drawable.ic_notification_icon)
                            .setAutoCancel(true)
                            .setContentIntent(notifyPendingIntent)
                            .setTicker(getText(R.string.app_name))
                            .build();

            startForeground(ONGOING_NOTIFICATION_ID, notification);
        } else {
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_DEFAULT_IMPORTANCE)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText("Active")
                    .setSmallIcon(R.drawable.ic_notification_icon)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build();

            startForeground(ONGOING_NOTIFICATION_ID, notification);
        }
        return Service.START_NOT_STICKY;
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private void createNotificationChannel(String channelId, String channelName) {
        NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW);
        channel.setLightColor(Color.BLUE);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
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
            }
        }
    };

    @Override
    public void onCreate() {
        Log.d(TAG, "Service started");
        super.onCreate();

        // ANT+
        initAntPlus();

        // Bluetooth LE
        mBluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        assert mBluetoothManager != null;
        BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();
        // continue without proper Bluetooth support
        if (!checkBluetoothSupport(bluetoothAdapter)) {
            Log.e(TAG, "Bluetooth LE isn't supported. This won't run");
            stopSelf();
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
        initialised = true;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "onTaskRemoved called");
        super.onTaskRemoved(rootIntent);
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");
        super.onDestroy();
        if (initialised) {
            // stop BLE
            BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                stopServer();
                stopAdvertising();
            }

            unregisterReceiver(mBluetoothReceiver);

            // stop ANT+
            if (bsdReleaseHandle != null)
                bsdReleaseHandle.close();

            if (bcReleaseHandle != null)
                bcReleaseHandle.close();

            if (hrReleaseHandle != null)
                hrReleaseHandle.close();

            combinedSensorConnected = false;
        }
    }


    /**
     * Begin advertising over Bluetooth that this device is connectable
     */
    private void startAdvertising() {
        BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Failed to create bluetooth adapter");
            return;
        }
        mBluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (mBluetoothLeAdvertiser == null) {
            Log.w(TAG, "Failed to create advertiser");
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();

        AdvertiseData advData = new AdvertiseData.Builder()
                .setIncludeTxPowerLevel(true)
                .addServiceUuid(new ParcelUuid(CSCProfile.CSC_SERVICE))
                .addServiceUuid(new ParcelUuid(CSCProfile.HR_SERVICE))
                .addServiceUuid(new ParcelUuid(CSCProfile.RSC_SERVICE))
                .build();

        AdvertiseData advScanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();

        mBluetoothLeAdvertiser
                .startAdvertising(settings, advData, advScanResponse, mAdvertiseCallback);
    }

    /**
     * Stop Bluetooth advertisements
     */
    private void stopAdvertising() {
        if (mBluetoothLeAdvertiser == null) return;

        mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
    }

    /**
     * Initialize the GATT server
     */
    private void startServer() {
        mBluetoothGattServer = mBluetoothManager.openGattServer(this, mGattServerCallback);
        if (mBluetoothGattServer == null) {
            Log.w(TAG, "Unable to create GATT server");
            return;
        }

        btServiceInitialized = false;
        // TODO: enable either 1 of them or both of them according to user selection
        if (mBluetoothGattServer.addService(CSCProfile.createCSCService((byte)(CSCProfile.CSC_FEATURE_WHEEL_REV | CSCProfile.CSC_FEATURE_CRANK_REV)))) {
            Log.d(TAG, "CSCP enabled!");
        } else {
            Log.d(TAG, "Failed to add csc service to bluetooth layer!");
        }

        // We cannot add another service until the callback for the previous service has completed
        while (!btServiceInitialized);

        btServiceInitialized = false;
        if (mBluetoothGattServer.addService(CSCProfile.createHRService())) {
            Log.d(TAG, "HR enabled!");
        } else {
            Log.d(TAG, "Failed to add hr service to bluetooth layer!");
        }

        // We cannot add another service until the callback for the previous service has completed
        while (!btServiceInitialized);

        if (mBluetoothGattServer.addService(CSCProfile.createRscService())) {
            Log.d(TAG, "RSC enabled!");
        } else {
            Log.d(TAG, "Failed to add rsc service to bluetooth layer");
        }

        Log.d(TAG, "Enumerating (" +  mBluetoothGattServer.getServices().size() + ") BT services");
        for (BluetoothGattService b : mBluetoothGattServer.getServices()) {
            Log.d(TAG,"Services registered: " +  b.getUuid().toString());
        }

        // start periodicUpdate, sending notification to subscribed device and UI
        handler.post(periodicUpdate);
    }

    /**
     * Shut down the GATT server
     */
    private void stopServer() {
        if (mBluetoothGattServer == null) return;

        // stop periodicUpdate
        handler.removeCallbacksAndMessages(null);
        mBluetoothGattServer.close();
    }

    /**
     * Callback to receive information about the advertisement process
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

            // send to registered BLE devices. It's a no-op if there is no GATT client
            notifyRegisteredDevices();

            // update UI by sending broadcast to our main activity
            Intent i = new Intent("idv.markkuo.cscblebridge.ANTDATA");
            i.putExtra("speed", lastSpeed);
            i.putExtra("cadence", lastCadence);
            i.putExtra("hr", lastHR);
            i.putExtra("ss_distance", lastSSDistance);
            i.putExtra("ss_speed", lastSSSpeed);
            i.putExtra("ss_stride_count", lastStridePerMinute);
            i.putExtra("speed_timestamp", lastSpeedTimestamp);
            i.putExtra("cadence_timestamp", lastCadenceTimestamp);
            i.putExtra("hr_timestamp", lastHRTimestamp);
            i.putExtra("ss_distance_timestamp", lastSSDistanceTimestamp);
            i.putExtra("ss_speed_timestamp", lastSSSpeedTimestamp);
            i.putExtra("ss_stride_count_timestamp", lastSSStrideCountTimestamp);
            Log.v(TAG, "Updating UI: speed:" + lastSpeed
                    + ", cadence:" + lastCadence +
                    ", hr " + lastHR +
                    ", speed_ts:" + lastSpeedTimestamp +
                    ", cadence_ts:" + lastCadenceTimestamp +
                    ", " + lastHRTimestamp +
                    ", ss_distance: " + lastSSDistance +
                    ", ss_distance_timestamp: " + lastSSDistanceTimestamp +
                    ", ss_speed: " + lastSSSpeed +
                    ", ss_speed_timestamp: " + lastSSSpeedTimestamp +
                    ", ss_stride_count: " + lastStridePerMinute +
                    ", ss_stride_count_timestamp: " + lastSSStrideCountTimestamp);
            sendBroadcast(i);
        }
    };

    /**
     * Send a CSC service notification to any devices that are subscribed
     * to the characteristic
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
            BluetoothGattService service = mBluetoothGattServer.getService(CSCProfile.CSC_SERVICE);
            if (service != null) {
                BluetoothGattCharacteristic measurementCharacteristic = mBluetoothGattServer
                        .getService(CSCProfile.CSC_SERVICE)
                        .getCharacteristic(CSCProfile.CSC_MEASUREMENT);
                if (!measurementCharacteristic.setValue(data)) {
                    Log.w(TAG, "CSC Measurement data isn't set properly!");
                }
                // false is used to send a notification
                mBluetoothGattServer.notifyCharacteristicChanged(device, measurementCharacteristic, false);
            } else {
                Log.v(TAG, "Service " + CSCProfile.CSC_SERVICE + " was not found as an installed service");
            }

            service = mBluetoothGattServer.getService(CSCProfile.HR_SERVICE);
            if (service != null) {
                Log.v(TAG, "Processing Heart Rate");

                BluetoothGattCharacteristic measurementCharacteristic = mBluetoothGattServer
                        .getService(CSCProfile.HR_SERVICE)
                        .getCharacteristic(CSCProfile.HR_MEASUREMENT);

                byte[] hrData = CSCProfile.getHR(lastHR, lastHRTimestamp);
                if (!measurementCharacteristic.setValue(hrData)) {
                    Log.w(TAG, "HR  Measurement data isn't set properly!");
                }
                mBluetoothGattServer.notifyCharacteristicChanged(device, measurementCharacteristic, false);
            } else {
                Log.v(TAG, "Service " + CSCProfile.HR_SERVICE + " was not found as an installed service");
            }

            service = mBluetoothGattServer.getService(CSCProfile.RSC_SERVICE);
            if (service != null) {
                Log.v(TAG, "Processing Running Speed and Cadence sensor");

                BluetoothGattCharacteristic measurementCharacteristic = mBluetoothGattServer
                        .getService(CSCProfile.RSC_SERVICE)
                        .getCharacteristic(CSCProfile.RSC_MEASUREMENT);

                byte[] rscData = CSCProfile.getRsc(lastSSDistance, lastSSSpeed, lastStridePerMinute);
                if (!measurementCharacteristic.setValue(rscData)) {
                    Log.w(TAG, "RSC Measurement data isn't set properly!");
                }
                mBluetoothGattServer.notifyCharacteristicChanged(device, measurementCharacteristic, false);
            } else {
                Log.v(TAG, "Service " + CSCProfile.RSC_SERVICE + " was not found as an installed service");
            }
        }
    }

    private final BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {

        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            Log.i(TAG, "onServiceAdded(): status:" + status + ", service:" + service);
            // Sets up for next service to be added
            btServiceInitialized = true;
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (mRegisteredDevices.contains(device)) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "BluetoothDevice DISCONNECTED: " + device.getName() + " [" + device.getAddress() + "]");
                    //Remove device from any active subscriptions
                    mRegisteredDevices.remove(device);
                } else {
                    Log.i(TAG, "onConnectionStateChange() status:" + status + "->" + newState + ", device" + device);
                }
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
            } else if (CSCProfile.RSC_MEASUREMENT.equals(characteristic.getUuid())) {
                    Log.i(TAG, "READ RSC Measurement");
                mBluetoothGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        null);
            } else if (CSCProfile.RSC_FEATURE.equals(characteristic.getUuid())) {
                Log.i(TAG, "READ RSC Feature");
                mBluetoothGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        CSCProfile.getRscFeature());
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

    /**
     * Initialize searching for all supported sensors
     */
    private void initAntPlus() {
        Log.d(TAG, "requesting ANT+ access");

        startSpeedSensorSearch();
        startCadenceSensorSearch();
        startHRSensorSearch();
        startStrideSdmSensorSearch();
    }

    /**
     * Initializes the speed sensor search
     */
    protected void startSpeedSensorSearch() {
        //Release the old access if it exists
        if (bsdReleaseHandle != null)
            bsdReleaseHandle.close();

        combinedSensorConnected = false;

        // starts speed sensor search
        bsdReleaseHandle = AntPlusBikeSpeedDistancePcc.requestAccess(this, 0, 0, false,
                mBSDResultReceiver, mBSDDeviceStateChangeReceiver);

        // send initial state for UI
        Intent i = new Intent("idv.markkuo.cscblebridge.ANTDATA");
        i.putExtra("bsd_service_status", "SEARCHING");
        sendBroadcast(i);
    }

    /**
     * Initializes the cadence sensor search
     */
    protected void startCadenceSensorSearch() {
        //Release the old access if it exists
        if (bcReleaseHandle != null)
            bcReleaseHandle.close();

        // starts cadence sensor search
        bcReleaseHandle = AntPlusBikeCadencePcc.requestAccess(this, 0, 0, false,
                mBCResultReceiver, mBCDeviceStateChangeReceiver);

        // send initial state for UI
        Intent i = new Intent("idv.markkuo.cscblebridge.ANTDATA");
        i.putExtra("bc_service_status", "SEARCHING");
        sendBroadcast(i);
    }

    /**
     * Initializes the HR  sensor search
     */
    protected void startHRSensorSearch() {
        //Release the old access if it exists
        if (hrReleaseHandle != null)
            hrReleaseHandle.close();

        // starts hr sensor search
        hrReleaseHandle = AntPlusHeartRatePcc.requestAccess(this, 0, 0,
                mHRResultReceiver, mHRDeviceStateChangeReceiver);

        // send initial state for UI
        Intent i = new Intent("idv.markkuo.cscblebridge.ANTDATA");
        i.putExtra("hr_service_status", "SEARCHING");
        sendBroadcast(i);
    }

    /**
     * Initialized the Stride SDM (Stride based Speed and Distance Monitor) sensor search
     *
     * ex. Garmin Foot Pod
     */
    protected void startStrideSdmSensorSearch() {
        if (ssReleaseHandle != null)
            ssReleaseHandle.close();

        ssReleaseHandle = AntPlusStrideSdmPcc.requestAccess(this, 0, 0,
                mSSResultReceiver, mSSDeviceStateChangeReceiver);
        Intent i = new Intent("idv.markkuo.cscblebridge.ANTDATA");
        i.putExtra("ss_service_status", "SEARCHING");
        sendBroadcast(i);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /**
     * Get the services for communicating with it
     */
    public class LocalBinder extends Binder {
        CSCService getService() {
            return CSCService.this;
        }
    }

}
