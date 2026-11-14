package top.imsyy.splayer_next.android.lyricon

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.proify.lyricon.provider.LyriconProvider
import top.imsyy.splayer_next.android.playback.PlaybackManager

/**
 * 词幕（Lyricon）进度同步器。
 *
 * 为什么放在原生侧：JS 的 tick 依赖 WebView 存活，息屏/后台冻结后就停了，
 * 状态栏歌词会卡住。这里用主线程 Handler 直接读 PlaybackManager 的位置与播放状态，
 * 只要进程活着就能持续同步。
 *
 * 间隔 250ms，与播放器自身进度推送频率一致。
 */
object LyriconPositionTicker {
  private const val TAG = "LyriconTicker"

  /** 同步间隔（毫秒） */
  private const val INTERVAL_MS = 250L

  private val handler = Handler(Looper.getMainLooper())

  private var provider: LyriconProvider? = null
  private var playbackManager: PlaybackManager? = null

  private val task =
    object : Runnable {
      override fun run() {
        tick()
        handler.postDelayed(this, INTERVAL_MS)
      }
    }

  /** 启动同步（幂等） */
  @Synchronized
  fun start(context: Context, provider: LyriconProvider) {
    this.provider = provider
    if (playbackManager == null) {
      playbackManager = PlaybackManager.getInstance(context)
    }
    handler.removeCallbacks(task)
    handler.postDelayed(task, INTERVAL_MS)
    Log.i(TAG, "进度同步已启动")
  }

  /** 停止同步 */
  @Synchronized
  fun stop() {
    handler.removeCallbacks(task)
    provider = null
    playbackManager = null
    Log.i(TAG, "进度同步已停止")
  }

  private fun tick() {
    val player = provider?.player ?: return
    val manager = playbackManager ?: return
    try {
      player.setPosition(manager.getLyricPositionMs())
      player.setPlaybackState(manager.isPlayingForExternal())
    } catch (error: Throwable) {
      // 中心服务短暂不可用时静默跳过，下个 tick 自愈
      Log.w(TAG, "同步播放进度失败", error)
    }
  }
}
