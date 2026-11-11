package cn.local.message.message_aggregation

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

object BridgeEvents {
    var sink: EventChannel.EventSink? = null
    private val main = Handler(Looper.getMainLooper())
    private val emit = Runnable { sink?.success(true) }
    fun changed() { main.post {
        // Batch a burst of persisted notifications into one platform-channel reload.
        main.removeCallbacks(emit)
        main.postDelayed(emit, 100)
    } }
}
