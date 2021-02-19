package idv.markkuo.cscblebridge.service.ble

import idv.markkuo.cscblebridge.service.ant.AntDevice
import java.util.*
import kotlin.experimental.and
import kotlin.experimental.or

/**
 * Type of bluetooth service
 *
 * NOTE: Make sure these are objects since reflection is used
 */
sealed class BleServiceType(val serviceId: UUID, val measurement: UUID, val feature: UUID?) {
    companion object {
        val serviceTypes = listOf(CscService, RscService, HrService)
        var CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    object CscService: BleServiceType(
            // https://www.bluetooth.com/specifications/gatt/services/
            /** Cycling Speed and Cadence */
            UUID.fromString("00001816-0000-1000-8000-00805f9b34fb"),
            // https://www.bluetooth.com/specifications/gatt/characteristics/
            /** Mandatory Characteristic: CSC Measurement */
            UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb"),
            /** Mandatory Characteristic: CSC Feature */
            UUID.fromString("00002a5c-0000-1000-8000-00805f9b34fb")
    ) {
        /** supported CSC Feature bit: Speed sensor  */
        private const val CSC_FEATURE_WHEEL_REV: Byte = 0x1
        /** supported CSC Feature bit: Cadence sensor  */
        private const val CSC_FEATURE_CRANK_REV: Byte = 0x2

        private var currentFeature = CSC_FEATURE_WHEEL_REV or CSC_FEATURE_CRANK_REV

        override fun getSupportedFeatures(): ByteArray? {
            val data = ByteArray(2)
            // always leave the second byte 0
            data[0] = currentFeature
            return data
        }

        override fun getBleData(antDevices: List<AntDevice>): ByteArray? {
            if (antDevices.size > 2 || antDevices.isEmpty()) {
                throw IllegalArgumentException("2 ANT+ devices (speed vs cadence) can be paired, Or 1 ANT+ device. But found ${antDevices.size}")
            }

            val bsdDevice = antDevices.firstOrNull { it is AntDevice.BsdDevice } as AntDevice.BsdDevice?
            val bcDevice = (antDevices.firstOrNull { it is AntDevice.BcDevice }) as AntDevice.BcDevice?

            if (bsdDevice == null && bcDevice == null) {
                throw IllegalArgumentException("Found devices which were not bsd devices")
            }
            val bsdFeature = if (bsdDevice != null) {
                CSC_FEATURE_WHEEL_REV
            } else {
                0
            }
            val bcFeature = if (bcDevice != null) {
                CSC_FEATURE_CRANK_REV
            } else {
                0
            }

            currentFeature = bsdFeature or bcFeature

            val data: MutableList<Byte> = ArrayList()
            data.add((currentFeature and 0x3)) // only preserve bit 0 and 1

            if ((currentFeature and CSC_FEATURE_WHEEL_REV).toInt() == CSC_FEATURE_WHEEL_REV.toInt()) {
                // cumulative wheel revolutions (uint32), only take the last 4 bytes
                bsdDevice?.let {
                    data.add(it.cumulativeWheelRevolution.toByte())
                    data.add((it.cumulativeWheelRevolution shr java.lang.Byte.SIZE).toByte())
                    data.add((it.cumulativeWheelRevolution shr java.lang.Byte.SIZE * 2).toByte())
                    data.add((it.cumulativeWheelRevolution shr java.lang.Byte.SIZE * 3).toByte())

                    // Last Wheel Event Time (uint16),  unit is 1/1024s, only take the last 2 bytes
                    data.add(it.lastWheelEventTime.toByte())
                    data.add((it.lastWheelEventTime shr java.lang.Byte.SIZE).toByte())
                }
            }
            if ((currentFeature and CSC_FEATURE_CRANK_REV).toInt() == CSC_FEATURE_CRANK_REV.toInt()) {
                bcDevice?.let {
                    // Cumulative Crank Revolutions (uint16)
                    data.add(it.cumulativeCrankRevolution.toByte())
                    data.add((it.cumulativeCrankRevolution shr java.lang.Byte.SIZE).toByte())

                    // Last Crank Event Time (uint16) uint is 1/1024s
                    data.add(it.crankEventTime.toByte())
                    data.add((it.crankEventTime shr java.lang.Byte.SIZE).toByte())
                }
            }

            // convert to primitive byte array
            val byteArray = ByteArray(data.size)
            for (i in data.indices) {
                byteArray[i] = data[i]
            }
            return byteArray
        }
    }

    object HrService: BleServiceType(
            UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb"),
            null
    ) {
        override fun getSupportedFeatures(): ByteArray? {
            return null
        }

        override fun getBleData(antDevices: List<AntDevice>): ByteArray? {
            if (antDevices.size > 1 || antDevices.isEmpty()) {
                throw IllegalArgumentException("Only one HR ANT+ device can be selected at a time")
            }
            val antDevice = antDevices.first()
            if (antDevice !is AntDevice.HRDevice) {
                throw IllegalArgumentException("Unable to get BLE Data for HR device with $antDevice")
            }

            // https://www.bluetooth.com/wp-content/uploads/Sitecore-Media-Library/Gatt/Xml/Characteristics/org.bluetooth.characteristic.heart_rate_measurement.xml
            val data: MutableList<Byte> = ArrayList()

            // Add the Flags, for our use they're all 0s
            data.add(0.toByte())
            data.add(antDevice.hr.toByte())

            // convert to primitive byte array
            val byteArray = ByteArray(data.size)
            for (i in data.indices) {
                byteArray[i] = data[i]
            }
            return byteArray
        }
    }

    object RscService: BleServiceType(
            UUID.fromString("00001814-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00002A53-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("00002A54-0000-1000-8000-00805f9b34fb")
    ) {
        private const val RSC_NO_FEATURES: Byte = 0
        override fun getSupportedFeatures(): ByteArray? {
            val data = ByteArray(1)
            data[0] = RSC_NO_FEATURES
            return data
        }

        // https://www.bluetooth.com/wp-content/uploads/Sitecore-Media-Library/Gatt/Xml/Characteristics/org.bluetooth.characteristic.rsc_measurement.xml
        override fun getBleData(antDevices: List<AntDevice>): ByteArray? {
            if (antDevices.size > 1 || antDevices.isEmpty()) {
                throw IllegalArgumentException("Only one HR ANT+ device can be selected at a time, found ${antDevices.size}")
            }
            val antDevice = antDevices.first()
            if (antDevice !is AntDevice.SSDevice) {
                throw IllegalArgumentException("Unable to get BLE Data for RSC device with $antDevice")
            }

            val data: MutableList<Byte> = ArrayList()
            // Instantanious stride length, total distance and walking or running could be calculated, but are not supported for now
            data.add(0.toByte())

            // Instantaneous Speed; Unit is in m/s with a resolution of 1/256 s (uint16)
            val wholeNumber = antDevice.ssSpeed.toInt()
            val decimalPlaces = binaryDecimalToByte(antDevice.ssSpeed, wholeNumber)
            data.add(decimalPlaces)
            data.add(wholeNumber.toByte())

            // Instantanious Cadence, Unit is in 1/minute (or RPM) with a resolutions of 1 1/min (or 1 RPM) (uint8)
            data.add(antDevice.stridePerMinute.toByte())

            // convert to primitive byte array
            val byteArray = ByteArray(data.size)
            for (i in data.indices) {
                byteArray[i] = data[i]
            }
            return byteArray
        }

        private fun binaryDecimalToByte(lastSSSpeed: Float, wholeNumber: Int): Byte {
            var number: Double
            var fraction: Double
            var integralPart: Int
            var b = 0
            fraction = (lastSSSpeed - wholeNumber).toDouble()
            for (i in 7 downTo 0) {
                integralPart = (fraction * 2).toInt()
                if (integralPart == 1) {
                    b = b or 1 shl i
                }
                number = fraction * 2
                fraction = number - integralPart
            }
            return b.toByte()
        }
    }

    abstract fun getSupportedFeatures(): ByteArray?
    abstract fun getBleData(antDevice: List<AntDevice>): ByteArray?
}