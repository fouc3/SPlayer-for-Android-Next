package top.imsyy.splayer_next.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * 网络状态插件
 *
 * 供前端按「Wi-Fi / 移动数据」区分在线音质。WebView 的 navigator.connection 在 Android
 * 上既不可靠也拿不到「是否计费网络」的准确结论，统一走 ConnectivityManager。
 */
@CapacitorPlugin(name = "AndroidNetwork")
class AndroidNetworkPlugin : Plugin() {
  private val connectivity: ConnectivityManager?
    get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

  /** 传输类型：wifi / cellular / ethernet / vpn / other / none */
  private fun classify(caps: NetworkCapabilities?): String {
    if (caps == null) return "none"
    return when {
      caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
      else -> "other"
    }
  }

  /**
   * 查询当前网络类型
   * @returns type: wifi / cellular / ethernet / vpn / bluetooth / other / none
   *          metered: 是否为计费网络（移动数据通常为 true）
   *          connected: 是否有可用网络
   */
  @PluginMethod
  fun getStatus(call: PluginCall) {
    val manager = connectivity
    if (manager == null) {
      // 无 ConnectivityManager 时按「未知但可用」处理，避免前端误判为离线降档
      val fallback = JSObject()
      fallback.put("type", "other")
      fallback.put("metered", false)
      fallback.put("connected", true)
      call.resolve(fallback)
      return
    }
    try {
      val network: Network? = manager.activeNetwork
      val caps = network?.let { manager.getNetworkCapabilities(it) }
      val result = JSObject()
      result.put("type", classify(caps))
      result.put("connected", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
      result.put("metered", manager.isActiveNetworkMetered)
      call.resolve(result)
    } catch (error: Exception) {
      call.reject("NETWORK_STATUS_FAILED", error)
    }
  }
}
