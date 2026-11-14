package top.imsyy.splayer_next.android.lyricon

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * 词幕（Lyricon）状态栏歌词推送插件。
 *
 * 前端在播放/切歌/进度变化时调用，把已解析 lyrics 与播放状态转交 [LyriconBridge]。
 * 所有方法均在失败时安全降级（resolve 带 available=false），绝不抛异常影响播放。
 */
@CapacitorPlugin(name = "AndroidLyricon")
class AndroidLyriconPlugin : Plugin() {
  /** 启用并注册 Provider */
  @PluginMethod
  fun enable(call: PluginCall) {
    val available = LyriconBridge.ensureRegistered(context)
    val res = JSObject()
    res.put("available", available)
    call.resolve(res)
  }

  /** 关闭并释放 Provider */
  @PluginMethod
  fun disable(call: PluginCall) {
    LyriconBridge.release()
    call.resolve()
  }

  /** 是否已就绪 */
  @PluginMethod
  fun isAvailable(call: PluginCall) {
    val res = JSObject()
    res.put("available", LyriconBridge.isAvailable)
    call.resolve(res)
  }

  /**
   * 推送整首歌与逐字歌词
   * 入参：linesJson / songId / name / artist / durationMs / showTranslation / showRoma
   */
  @PluginMethod
  fun pushSong(call: PluginCall) {
    val linesJson = call.getString("linesJson", "[]") ?: "[]"
    val res = JSObject()
    if (!LyriconBridge.isAvailable) {
      res.put("ok", false)
      res.put("reason", "not_registered")
      call.resolve(res)
      return
    }
    val ok =
      LyriconBridge.pushSong(
        linesJson = linesJson,
        songId = call.getString("songId", "") ?: "",
        name = call.getString("name", "") ?: "",
        artist = call.getString("artist", "") ?: "",
        durationMs = call.getLong("durationMs", 0L) ?: 0L,
        hasTranslation = call.getBoolean("showTranslation", false) ?: false,
        hasRoma = call.getBoolean("showRoma", false) ?: false,
      )
    res.put("ok", ok)
    call.resolve(res)
  }

  /** 同步播放状态与进度：playing / positionMs */
  @PluginMethod
  fun setState(call: PluginCall) {
    val res = JSObject()
    if (!LyriconBridge.isAvailable) {
      res.put("ok", false)
      call.resolve(res)
      return
    }
    val playing = call.getBoolean("playing", false) ?: false
    val positionMs = call.getLong("positionMs", 0L) ?: 0L
    LyriconBridge.setPlaybackState(playing)
    LyriconBridge.setPosition(positionMs)
    res.put("ok", true)
    call.resolve(res)
  }

  /** 清空当前歌曲（停止推送歌词，例如退出播放） */
  @PluginMethod
  fun clearSong(call: PluginCall) {
    LyriconBridge.clearSong()
    call.resolve()
  }
}
