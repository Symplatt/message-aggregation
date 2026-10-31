package cn.local.message.message_aggregation

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

object BridgeEvents {
    var sink: EventChannel.EventSink? = null
    private val main = Handler(Looper.getMainLooper())
    fun changed() { main.post { sink?.success(true) } }
}
