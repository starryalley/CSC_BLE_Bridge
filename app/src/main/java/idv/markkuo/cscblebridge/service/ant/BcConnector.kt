package idv.markkuo.cscblebridge.service.ant

import android.content.Context
import com.dsi.ant.plugins.antplus.pcc.AntPlusBikeCadencePcc
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle


class BcConnector(context: Context, listener: DeviceManagerListener<AntDevice.BcDevice>, val isCombinedSensor: Boolean = false): AntDeviceConnector<AntPlusBikeCadencePcc, AntDevice.BcDevice>(context, listener) {
    override fun requestAccess(context: Context, resultReceiver: AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeCadencePcc>, stateChangedReceiver: AntPluginPcc.IDeviceStateChangeReceiver, deviceNumber: Int): PccReleaseHandle<AntPlusBikeCadencePcc> {
        return AntPlusBikeCadencePcc.requestAccess(context, deviceNumber, 0, false, resultReceiver, stateChangedReceiver)
    }

    override fun subscribeToEvents(pcc: AntPlusBikeCadencePcc) {
        pcc.subscribeCalculatedCadenceEvent { _, _, calculatedCadence ->
            val device = getDevice(pcc)
            device.cadence = calculatedCadence.toInt()
            listener.onDataUpdated(device)
        }

        pcc.subscribeRawCadenceDataEvent { estTimestamp, _, timestampOfLastEvent, cumulativeRevolutions ->
            val device = getDevice(pcc)
            device.cumulativeCrankRevolution = cumulativeRevolutions
            device.crankEventTime = (timestampOfLastEvent.toDouble() * 1024.0).toLong()
            device.cadenceTimestamp = estTimestamp
            listener.onDataUpdated(device)
        }


        if (pcc.isSpeedAndCadenceCombinedSensor && !isCombinedSensor) {
            listener.onCombinedSensor(this, pcc.antDeviceNumber)
        }
    }

    override fun init(deviceNumber: Int, deviceName: String): AntDevice.BcDevice {
        return AntDevice.BcDevice(deviceNumber, deviceName, 0, 0L, 0L, 0L)
    }
}