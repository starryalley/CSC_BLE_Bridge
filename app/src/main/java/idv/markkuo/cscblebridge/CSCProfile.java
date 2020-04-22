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
    // Cycling Speed and Cadence	org.bluetooth.service.cycling_speed_and_cadence	0x1816	GSS
    public static UUID CSC_SERVICE = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb");
    //Cycling Power	org.bluetooth.service.cycling_power	0x1818	GSS

    // https://www.bluetooth.com/specifications/gatt/characteristics/
    // Mandatory Characteristic: CSC Measurement	org.bluetooth.characteristic.csc_measurement	0x2A5B
    public static UUID CSC_MEASUREMENT = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb");
    // https://www.bluetooth.com/specifications/gatt/descriptors/
    // Mandatory Client Characteristic Config Descriptor
    public static UUID CLIENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Mandatory Characteristic: CSC Feature	org.bluetooth.characteristic.csc_feature	0x2A5C
    public static UUID CSC_FEATURE = UUID.fromString("00002a5c-0000-1000-8000-00805f9b34fb");

    // CSC Feature bit
    public static final byte CSC_FEATURE_WHEEL_REV = 0x1;
    public static final byte CSC_FEATURE_CRANK_REV = 0x2;
    public static final byte CSC_FEATURE_MULTI_SENSOR_LOC = 0x4;

    // current implemented feature in this app
    private static byte currentFeature = CSC_FEATURE_WHEEL_REV | CSC_FEATURE_CRANK_REV;

    /**
     * Return a configured {@link BluetoothGattService} instance for the
     * Cycling Speed and Cadence Service
     */
    public static BluetoothGattService createCSCService() {
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

    public static void setFeature(byte feature) {
        currentFeature = feature;
    }

    // https://www.bluetooth.com/wp-content/uploads/Sitecore-Media-Library/Gatt/Xml/Characteristics/org.bluetooth.characteristic.csc_measurement.xml
    public static byte[] getMeasurement(long cumulativeRevolution, int lastWheelEventTime) {
        List<Byte> data = new ArrayList<Byte>();
        data.add((byte) (currentFeature & 0x3)); // only preserve bit 0 and 1
        if ((currentFeature & CSC_FEATURE_WHEEL_REV) == CSC_FEATURE_WHEEL_REV) {
            // cumulative wheel revolutions (uint32)
            data.add((byte) cumulativeRevolution);
            data.add((byte) (cumulativeRevolution >> Byte.SIZE));
            data.add((byte) (cumulativeRevolution >> Byte.SIZE * 2));
            data.add((byte) (cumulativeRevolution >> Byte.SIZE * 3));

            // Last Wheel Event Time (uint16),  unit is 1/1024s
            data.add((byte) lastWheelEventTime);
            data.add((byte) (lastWheelEventTime >> Byte.SIZE));
        }
        if ((currentFeature & CSC_FEATURE_CRANK_REV) == CSC_FEATURE_CRANK_REV) {
            Log.wtf(TAG, "This isn't implemented yet");
            // Cumulative Crank Revolutions (uint16)

            // Last Crank Event Time (uint16) uint is 1/1024s

        }

        // convert to primitive byte array
        byte[] byteArray = new byte[data.size()];
        for (int i = 0; i < data.size(); i++) {
            byteArray[i] = data.get(i);
        }
        Log.v(TAG, "CSC Measurement: 0x" + bytesToHex(byteArray));
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
        data[0] = currentFeature;
        data[1] = 0;
        return data;
    }
}
