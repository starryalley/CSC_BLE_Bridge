package idv.markkuo.cscblebridge.antrecyclerview

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import idv.markkuo.cscblebridge.R

class BroadcastButtonView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class BroadcastButtonViewState {
        NotSelected,
        Broadcasting
    }

    private val broadcastButtonBroadcast: Button
    private val bluetoothIcon: ImageView

    init {
        inflate(context, R.layout.broadcast_view, this)
        broadcastButtonBroadcast = findViewById(R.id.broadcast_button_broadcast)
        bluetoothIcon = findViewById(R.id.bluetooth)
    }

    fun setClickListener(clickListener: () -> Unit) {
        broadcastButtonBroadcast.setOnClickListener {
            clickListener()
        }
    }

    fun setState(state: BroadcastButtonViewState) {
        when (state) {
            BroadcastButtonViewState.NotSelected -> {
                broadcastButtonBroadcast.visibility = View.VISIBLE
                bluetoothIcon.visibility = View.GONE
            }
            BroadcastButtonViewState.Broadcasting -> {
                broadcastButtonBroadcast.visibility = View.GONE
                bluetoothIcon.visibility = View.VISIBLE
            }
        }
    }
}