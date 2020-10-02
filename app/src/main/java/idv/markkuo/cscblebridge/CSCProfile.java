package idv.markkuo.cscblebridge;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of the Bluetooth GATT Cycling Speed and Cadence Profile
 * https://www.bluetooth.com/specifications/gatt/
 */
public class CSCProfile {
    private static final String TAG = CSCProfile.class.getSimpleName();

    // https://www.bluetooth.com/specifications/gatt/services/
    /** Cycling Speed and Cadence */
    public static UUID CSC_SERVICE = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb");

    // https://www.bluetooth.com/specifications/gatt/characteristics/
    /** Mandatory Characteristic: CSC Measurement */
    public static UUID CSC_MEASUREMENT = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb");

    // https://www.bluetooth.com/specifications/gatt/descriptors/
    /** Mandatory Client Characteristic Config Descriptor */
    public static UUID CLIENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** Mandatory Characteristic: CSC Feature */
    public static UUID CSC_FEATURE = UUID.fromString("00002a5c-0000-1000-8000-00805f9b34fb");

    /** Heart Rate */
    public static UUID HR_SERVICE = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb");
    public static UUID HR_MEASUREMENT = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb");

    /** supported CSC Feature bit: Speed sensor */
    public static final byte CSC_FEATURE_WHEEL_REV = 0x1;
    /** supported CSC Feature bit: Cadence sensor */
    public static final byte CSC_FEATURE_CRANK_REV = 0x2;

    // this feature defined in spec is not supported
    //public static final byte CSC_FEATURE_MULTI_SENSOR_LOC = 0x4;

    // default implemented features
    private static byte currentFeature = CSC_FEATURE_WHEEL_REV | CSC_FEATURE_CRANK_REV;

    /**
     * Return a configured {@link BluetoothGattService} instance for the
     * Cycling Speed and Cadence Service
     */
    public static BluetoothGattService createCSCService(byte feature) {
        // Set supported feature: speed and/or cadence
        currentFeature = feature;

        BluetoothGattService service = new BluetoothGattService(CSC_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // CSC Measurement characteristic
        BluetoothGattCharacteristic cscMeasurement = new BluetoothGattCharacteristic(CSC_MEASUREMENT,
                //Read-only characteristic, supports notifications
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        BluetoothGattDescriptor configDescriptor = new BluetoothGattDescriptor(CLIENT_CONFIG,
                //Read/write descriptor
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        cscMeasurement.addDescriptor(configDescriptor);

        // CSC Feature characteristic
        BluetoothGattCharacteristic cscFeature = new BluetoothGattCharacteristic(CSC_FEATURE,
                //Read-only characteristic
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);

        service.addCharacteristic(cscMeasurement);
        service.addCharacteristic(cscFeature);

        return service;
    }

    /**
     * Return a configured {@link BluetoothGattService} instance for the
     * Heart Rate service
     */
    public static BluetoothGattService createHRService() {
        BluetoothGattService service = new BluetoothGattService(HR_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // CSC Measurement characteristic
        BluetoothGattCharacteristic hrMeasurement = new BluetoothGattCharacteristic(HR_MEASUREMENT,
                //Read-only characteristic, supports notifications
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        BluetoothGattDescriptor configDescriptor = new BluetoothGattDescriptor(CLIENT_CONFIG,
                //Read/write descriptor
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        hrMeasurement.addDescriptor(configDescriptor);

        service.addCharacteristic(hrMeasurement);

        return service;
    }
    // https://www.bluetooth.com/wp-content/uploads/Sitecore-Media-Library/Gatt/Xml/Characteristics/org.bluetooth.characteristic.csc_measurement.xml
    public static byte[] getMeasurement(long cumulativeWheelRevolution, int lastWheelEventTime,
                                        long cumulativeCrankRevolution, int lastCrankEventTime) {
        List<Byte> data = new ArrayList<>();
        data.add((byte) (currentFeature & 0x3)); // only preserve bit 0 and 1
        if ((currentFeature & CSC_FEATURE_WHEEL_REV) == CSC_FEATURE_WHEEL_REV) {
            // cumulative wheel revolutions (uint32), only take the last 4 bytes
            data.add((byte) cumulativeWheelRevolution);
            data.add((byte) (cumulativeWheelRevolution >> Byte.SIZE));
            data.add((byte) (cumulativeWheelRevolution >> Byte.SIZE * 2));
            data.add((byte) (cumulativeWheelRevolution >> Byte.SIZE * 3));

            // Last Wheel Event Time (uint16),  unit is 1/1024s, only take the last 2 bytes
            data.add((byte) lastWheelEventTime);
            data.add((byte) (lastWheelEventTime >> Byte.SIZE));
        }
        if ((currentFeature & CSC_FEATURE_CRANK_REV) == CSC_FEATURE_CRANK_REV) {
            // Cumulative Crank Revolutions (uint16)
            data.add((byte) cumulativeCrankRevolution);
            data.add((byte) (cumulativeCrankRevolution >> Byte.SIZE));

            // Last Crank Event Time (uint16) uint is 1/1024s
            data.add((byte) lastCrankEventTime);
            data.add((byte) (lastCrankEventTime >> Byte.SIZE));
        }

        // convert to primitive byte array
        byte[] byteArray = new byte[data.size()];
        for (int i = 0; i < data.size(); i++) {
            byteArray[i] = data.get(i);
        }
        Log.v(TAG, "CSC Measurement: 0x" + bytesToHex(byteArray));
        return byteArray;
    }

    // https://www.bluetooth.com/wp-content/uploads/Sitecore-Media-Library/Gatt/Xml/Characteristics/org.bluetooth.characteristic.heart_rate_measurement.xml
    public static byte[] getHR(int hr, long lastHRTime) {
        List<Byte> data = new ArrayList<>();

        // Add the Flags, for our use they're all 0s
        data.add((byte) (0b00000000));
        data.add((byte) (hr));

        // convert to primitive byte array
        byte[] byteArray = new byte[data.size()];
        for (int i = 0; i < data.size(); i++) {
            byteArray[i] = data.get(i);
        }
        Log.v(TAG, "HR Measurement: 0x" + bytesToHex(byteArray));
        return byteArray;
    }
    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    // https://www.bluetooth.com/wp-content/uploads/Sitecore-Media-Library/Gatt/Xml/Characteristics/org.bluetooth.characteristic.csc_feature.xml
    public static byte[] getFeature() {
        byte[] data = new byte[2];
        data[0] = currentFeature; // always leave the second byte 0
        return data;
    }
}
