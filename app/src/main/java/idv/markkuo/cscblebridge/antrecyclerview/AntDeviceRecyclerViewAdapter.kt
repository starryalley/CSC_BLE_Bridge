package idv.markkuo.cscblebridge.antrecyclerview

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import idv.markkuo.cscblebridge.service.ant.AntDevice
import idv.markkuo.cscblebridge.service.ble.BleServiceType

class AntDeviceRecyclerViewAdapter(private val deviceSelected: (device: AntDevice) -> Unit): RecyclerView.Adapter<AntDeviceViewHolder>() {
    private val deviceList = ArrayList<AntDevice>()
    private var selectedDevices: Map<BleServiceType, List<Int>>? = null

    fun updateDevices(devices: List<AntDevice>, selectedDevices: Map<BleServiceType, List<Int>>) {
        this.selectedDevices = selectedDevices
        deviceList.clear()
        deviceList.addAll(devices)
        // could do this better, but it seems to perform well enough with so few items
        notifyDataSetChanged()
    }

    fun addDevice(antDevice: AntDevice) {
        deviceList.add(antDevice)
        notifyItemInserted(deviceList.size - 1)
    }

    fun removeDevice(antDevice: AntDevice) {
        val index = deviceList.indexOf(antDevice)
        deviceList.remove(antDevice)
        notifyItemRemoved(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AntDeviceViewHolder {
        val view = AntDeviceView(parent.context)
        return AntDeviceViewHolder(view)
    }

    override fun getItemCount(): Int = deviceList.size

    override fun onBindViewHolder(holder: AntDeviceViewHolder, position: Int) {
        val antDevice = deviceList[position]
        holder.view.bind(antDevice, selectedDevices?.values?.flatten()?.contains(antDevice.deviceId) ?: false, deviceSelected)
    }
}