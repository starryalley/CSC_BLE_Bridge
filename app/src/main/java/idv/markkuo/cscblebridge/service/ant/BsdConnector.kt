package idv.markkuo.cscblebridge.service.ant

import android.content.Context
import android.util.Log
import com.dsi.ant.plugins.antplus.pcc.AntPlusBikeSpeedDistancePcc
import com.dsi.ant.plugins.antplus.pcc.AntPlusBikeSpeedDistancePcc.CalculatedSpeedReceiver
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceState
import com.dsi.ant.plugins.antplus.pcc.defines.EventFlag
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle
import java.math.BigDecimal
import java.util.*

class BsdConnector(context: Context, listener: DeviceManagerListener<AntDevice.BsdDevice>, val isCombinedSensor: Boolean = false): AntDeviceConnector<AntPlusBikeSpeedDistancePcc, AntDevice.BsdDevice>(context, listener) {

    companion object {
        private const val TAG = "BsdConnector"
        private val circumference = BigDecimal("2.095")
        private val msToKmSRatio = BigDecimal("3.6")
    }

    override fun requestAccess(context: Context,
                               resultReceiver: AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeSpeedDistancePcc>,
                               deviceStateChanged: AntPluginPcc.IDeviceStateChangeReceiver,
                               deviceNumber: Int): PccReleaseHandle<AntPlusBikeSpeedDistancePcc> {
        return AntPlusBikeSpeedDistancePcc.requestAccess(context, deviceNumber, 0, false, resultReceiver, deviceStateChanged)
    }

    override fun subscribeToEvents(pcc: AntPlusBikeSpeedDistancePcc) {
        pcc.subscribeCalculatedSpeedEvent(object : CalculatedSpeedReceiver(circumference) {
            override fun onNewCalculatedSpeed(estTimestamp: Long,
                                              eventFlags: EnumSet<EventFlag>, calculatedSpeed: BigDecimal) {
                val device = getDevice(pcc)
                // convert m/s to km/h
                device.lastSpeed = calculatedSpeed.multiply(msToKmSRatio).toFloat()
                listener.onDataUpdated(device)
            }
        })
        pcc.subscribeRawSpeedAndDistanceDataEvent { estTimestamp, _, timestampOfLastEvent, cumulativeRevolutions -> //estTimestamp - The estimated timestamp of when this event was triggered. Useful for correlating multiple events and determining when data was sent for more accurate data records.
            //eventFlags - Informational flags about the event.
            //timestampOfLastEvent - Sensor reported time counter value of last distance or speed computation (up to 1/200s accuracy). Units: s. Rollover: Every ~46 quadrillion s (~1.5 billion years).
            //cumulativeRevolutions - Total number of revolutions since the sensor was first connected. Note: If the subscriber is not the first PCC connected to the device the accumulation will probably already be at a value greater than 0 and the subscriber should save the first received value as a relative zero for itself. Units: revolutions. Rollover: Every ~9 quintillion revolutions.
            Log.v(TAG, "=> BSD: Cumulative revolution: $cumulativeRevolutions, lastEventTime: $timestampOfLastEvent")
            val device = getDevice(pcc)
            device.cumulativeWheelRevolution = cumulativeRevolutions
            device.lastWheelEventTime = (timestampOfLastEvent.toDouble() * 1024.0).toInt()
            device.lastSpeedTimestamp = estTimestamp
            listener.onDataUpdated(device)
        }

        if (pcc.isSpeedAndCadenceCombinedSensor && !isCombinedSensor) {
            listener.onCombinedSensor(this, pcc.antDeviceNumber)
        }
    }

    override fun init(deviceNumber: Int, deviceName: String): AntDevice.BsdDevice {
        return AntDevice.BsdDevice(deviceNumber, deviceName, 0f, 0L, 0, 0L)
    }

}