package idv.markkuo.cscblebridge.service.ant

import android.content.Context
import com.dsi.ant.plugins.antplus.pcc.AntPlusHeartRatePcc
import com.dsi.ant.plugins.antplus.pcc.AntPlusHeartRatePcc.IHeartRateDataReceiver
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle

class HRConnector(context: Context, listener: DeviceManagerListener<AntDevice.HRDevice>): AntDeviceConnector<AntPlusHeartRatePcc, AntDevice.HRDevice>(context, listener) {
    override fun requestAccess(context: Context, resultReceiver: AntPluginPcc.IPluginAccessResultReceiver<AntPlusHeartRatePcc>, stateChangedReceiver: AntPluginPcc.IDeviceStateChangeReceiver, deviceNumber: Int): PccReleaseHandle<AntPlusHeartRatePcc> {
        return AntPlusHeartRatePcc.requestAccess(context, deviceNumber, 0, resultReceiver, stateChangedReceiver)
    }

    override fun subscribeToEvents(pcc: AntPlusHeartRatePcc) {
        pcc.subscribeHeartRateDataEvent(IHeartRateDataReceiver { estTimestamp, _, computedHeartRate, heartBeatCount, heartBeatEventTime, dataState ->
            val device = getDevice(pcc)
            device.hr = computedHeartRate
            device.hrTimestamp = estTimestamp
            listener.onDataUpdated(device)
        })
    }

    override fun init(deviceNumber: Int, deviceName: String): AntDevice.HRDevice {
        return AntDevice.HRDevice(deviceNumber, deviceName, 0, 0L)
    }
}
