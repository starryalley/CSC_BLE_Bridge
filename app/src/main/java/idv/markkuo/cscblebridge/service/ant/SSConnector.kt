package idv.markkuo.cscblebridge.service.ant

import android.content.Context
import android.util.Log
import android.util.Pair
import com.dsi.ant.plugins.antplus.pcc.AntPlusHeartRatePcc
import com.dsi.ant.plugins.antplus.pcc.AntPlusStrideSdmPcc
import com.dsi.ant.plugins.antplus.pcc.AntPlusStrideSdmPcc.*
import com.dsi.ant.plugins.antplus.pcc.defines.EventFlag
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle
import java.util.*
import java.util.concurrent.Semaphore

class SSConnector(context: Context, listener: DeviceManagerListener<AntDevice.SSDevice>): AntDeviceConnector<AntPlusStrideSdmPcc, AntDevice.SSDevice>(context, listener) {

    companion object {
        private const val TAG = "SSConnector"
    }

    override fun requestAccess(
            context: Context,
            resultReceiver: AntPluginPcc.IPluginAccessResultReceiver<AntPlusStrideSdmPcc>,
            stateChangedReceiver: AntPluginPcc.IDeviceStateChangeReceiver,
            deviceNumber: Int): PccReleaseHandle<AntPlusStrideSdmPcc> {
        return requestAccess(context, deviceNumber, 0, resultReceiver, stateChangedReceiver)
    }

    override fun subscribeToEvents(pcc: AntPlusStrideSdmPcc) {
        // https://www.thisisant.com/developer/ant-plus/device-profiles#528_tab
        pcc.subscribeStrideCountEvent(object : IStrideCountReceiver {
            private val TEN_SECONDS_IN_MS = 10000
            private val FALLBACK_MAX_LIST_SIZE = 500
            private val ONE_MINUTE_IN_MS = 60000f
            private val strideList = LinkedList<Pair<Long, Long>>()
            private val lock = Semaphore(1)
            override fun onNewStrideCount(estTimestamp: Long, eventFlags: EnumSet<EventFlag>, cumulativeStrides: Long) {
                Thread {
                    try {
                        lock.acquire()
                        // Calculate number of strides per minute, updates happen around every 500 ms, this number
                        // may be off by that amount but it isn't too significant
                        strideList.addFirst(Pair(estTimestamp, cumulativeStrides))
                        var strideCount: Long = 0
                        var valueFound = false
                        var i = 0
                        for (p in strideList) {
                            // Cadence over the last 10 seconds
                            if (estTimestamp - p.first >= TEN_SECONDS_IN_MS) {
                                valueFound = true
                                strideCount = calculateStepsPerMin(estTimestamp, cumulativeStrides, p)
                                break
                            } else if (i + 1 == strideList.size) {
                                // No value was found yet, it has not been 10 seconds. Give an early rough estimate
                                strideCount = calculateStepsPerMin(estTimestamp, cumulativeStrides, p)
                            }
                            i++
                        }
                        while (valueFound && strideList.size >= i + 1 || strideList.size > FALLBACK_MAX_LIST_SIZE) {
                            strideList.removeLast()
                        }
                        val device = getDevice(pcc)
                        device.stridePerMinute = strideCount
                        device.stridePerMinuteTimestamp = estTimestamp
                        lock.release()
                        listener.onDataUpdated(device)
                    } catch (e: InterruptedException) {
                        Log.e(TAG, "Unable to acquire lock to update running cadence", e)
                    }
                }.start()
            }

            private fun calculateStepsPerMin(estTimestamp: Long, cumulativeStrides: Long, p: Pair<Long, Long>): Long {
                val elapsedTimeMs = estTimestamp - p.first.toFloat()
                return if (elapsedTimeMs == 0f) {
                    0
                } else ((cumulativeStrides - p.second) * (ONE_MINUTE_IN_MS / elapsedTimeMs)).toLong()
            }
        })

        pcc.subscribeDistanceEvent { estTimestamp, _, distance ->
            val device = getDevice(pcc)
            device.ssDistance = distance.toLong()
            device.ssDistanceTimestamp = estTimestamp
            listener.onDataUpdated(device)
        }

        pcc.subscribeInstantaneousSpeedEvent { estTimestamp, _, instantaneousSpeed ->
            val device = getDevice(pcc)
            device.ssSpeed = instantaneousSpeed.toFloat()
            device.ssSpeedTimestamp = estTimestamp
            listener.onDataUpdated(device)
        }
    }

    override fun init(deviceNumber: Int, deviceName: String): AntDevice.SSDevice {
        return AntDevice.SSDevice(deviceNumber, deviceName, 0L, 0L, 0f, 0L, 0L, 0L)
    }

}