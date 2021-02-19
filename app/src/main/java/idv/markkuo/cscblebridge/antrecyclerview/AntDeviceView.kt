package idv.markkuo.cscblebridge.antrecyclerview

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import idv.markkuo.cscblebridge.R
import idv.markkuo.cscblebridge.service.ant.AntDevice
import org.w3c.dom.Text

class AntDeviceView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val nameView: TextView
    private val typeView: TextView
    private val dataView: TextView
    private val background: LinearLayout
    private val broadcastButtonView: BroadcastButtonView

    init {
        inflate(context, R.layout.ant_list_item, this)
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        nameView = findViewById(R.id.ant_device_name)
        typeView = findViewById(R.id.ant_device_type)
        dataView = findViewById(R.id.ant_device_data)
        background = findViewById(R.id.ant_device_background)
        broadcastButtonView = findViewById(R.id.broadcast_button_view)
    }

    fun bind(antDevice: AntDevice, isSelected: Boolean, onClickListener: (antDevice: AntDevice) -> Unit) {
        val color = if (isSelected) {
            broadcastButtonView.setState(BroadcastButtonView.BroadcastButtonViewState.Broadcasting)
            context.resources.getColor(android.R.color.holo_blue_dark)
        } else {
            broadcastButtonView.setState(BroadcastButtonView.BroadcastButtonViewState.NotSelected)
            context.resources.getColor(android.R.color.black)
        }

        nameView.text = antDevice.deviceName
        nameView.setTextColor(color)
        typeView.text = antDevice.typeName
        dataView.text = antDevice.getDataString()
        background.setOnClickListener { onClickListener(antDevice) }
        broadcastButtonView.setClickListener { onClickListener(antDevice) }
    }
}