package top.imsyy.splayer_next.android.lyricon

import android.content.Context
import android.util.Log
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.LyriconFactory
import org.json.JSONArray

/**
 * 词幕（Lyricon）Provider 桥。
 *
 * 把 SPlayer-Next 已解析好的结构化歌词（逐字 words / 翻译 / 音译 / 对唱）映射为
 * Lyricon 的 Song / RichLyricLine / LyricWord 并推送给中心服务，用于状态栏歌词显示。
 *
 * 说明：
 * - SPlayer-Next 是普通应用，Lyricon Provider 就是面向普通应用提供的接入方式；
 * - 注册信息由 AndroidManifest 中的 lyricon_module/author/description 元数据声明；
 * - 时间单位与 Lyricon 一致，均为毫秒。
 */
object LyriconBridge {
  private const val TAG = "LyriconBridge"

  @Volatile private var provider: LyriconProvider? = null

  @Volatile private var currentSongId: String = ""

  /** 是否已成功创建 Provider（创建失败不影响播放器本体） */
  val isAvailable: Boolean
    get() = provider != null

  /** 取当前 Provider 实例（未注册时为 null） */
  fun providerOrNull(): LyriconProvider? = provider

  /**
   * 创建并注册 Provider（幂等）
   * @param context - 应用上下文
   * @returns 是否注册请求已发出
   */
  @Synchronized
  fun ensureRegistered(context: Context): Boolean {
    provider?.let { return it.register() }
    return try {
      val created = LyriconFactory.createProvider(context)
      provider = created
      created.service.addConnectionListener(
        io.github.proify.lyricon.provider.service.buildConnectionListener {
          onConnected {
            Log.i(TAG, "已连接词幕中心服务")
          }
          onReconnected {
            Log.i(TAG, "已重连词幕中心服务")
          }
          onDisconnected {
            Log.w(TAG, "与词幕中心服务断开")
          }
          onConnectTimeout {
            Log.w(TAG, "连接词幕中心服务超时（词幕未安装/未启用？）")
          }
        },
      )
      val ok = created.register()
      Log.i(TAG, "register ok=$ok pkg=${context.packageName}")
      ok
    } catch (error: Throwable) {
      // Lyricon 未安装或版本不匹配时仅记录，不抛出，避免拖垮播放器
      Log.w(TAG, "创建 Lyricon Provider 失败", error)
      provider = null
      false
    }
  }

  /** 取消注册并释放 */
  @Synchronized
  fun release() {
    val current = provider ?: return
    try {
      current.unregister()
      current.destroy()
    } catch (error: Throwable) {
      Log.w(TAG, "释放 Lyricon Provider 失败", error)
    } finally {
      provider = null
      currentSongId = ""
    }
  }

  /**
   * 推送一首歌的完整歌词
   *
   * @param linesJson - 与原生歌词浮层同构的 JSON（words/translatedLyric/romanLyric/startTime/endTime/isDuet）
   * @param songId - 歌曲唯一标识
   * @param name - 曲名
   * @param artist - 歌手串
   * @param durationMs - 总时长（毫秒）
   * @param hasTranslation - 是否请求展示翻译
   * @param hasRoma - 是否请求展示音译
   * @returns 是否推送成功
   */
  fun pushSong(
    linesJson: String,
    songId: String,
    name: String,
    artist: String,
    durationMs: Long,
    hasTranslation: Boolean,
    hasRoma: Boolean,
  ): Boolean {
    val current = provider ?: return false
    return try {
      val lines = parseLines(linesJson)
      val song =
        Song(
          id = songId,
          name = name,
          artist = artist,
          duration = durationMs,
          metadata = null,
          lyrics = lines,
        )
      val ok = current.player.setSong(song)
      Log.i(TAG, "setSong ok=$ok id=$songId lines=${lines.size}")
      if (ok) currentSongId = songId
      // 翻译/罗马音为展示开关，只控制展示，不生成内容
      runCatching {
        current.player.setDisplayTranslation(
          hasTranslation && lines.any { !it.translation.isNullOrEmpty() },
        )
      }
      runCatching { current.player.setDisplayRoma(hasRoma && lines.any { !it.roma.isNullOrEmpty() }) }
      ok
    } catch (error: Throwable) {
      Log.w(TAG, "推送歌词失败", error)
      false
    }
  }

  /** 同步播放状态（true=播放中） */
  fun setPlaybackState(playing: Boolean): Boolean =
    try {
      provider?.player?.setPlaybackState(playing) ?: false
    } catch (error: Throwable) {
      Log.w(TAG, "同步播放状态失败", error)
      false
    }

  /** 同步播放进度（毫秒） */
  fun setPosition(positionMs: Long): Boolean =
    try {
      provider?.player?.setPosition(positionMs.coerceAtLeast(0L)) ?: false
    } catch (error: Throwable) {
      Log.w(TAG, "同步播放进度失败", error)
      false
    }

  /** 当前是否已在推送某首歌 */
  fun hasSong(): Boolean = currentSongId.isNotEmpty()

  /** 清空当前歌曲（推送空 Song，让状态栏不再显示旧歌词） */
  fun clearSong(): Boolean =
    try {
      val ok =
        provider?.player?.setSong(
          Song(id = "", name = "", artist = "", duration = 0L, metadata = null, lyrics = emptyList()),
        ) ?: false
      currentSongId = ""
      ok
    } catch (error: Throwable) {
      Log.w(TAG, "清空歌词失败", error)
      false
    }

  /**
   * 解析歌词 JSON 为 Lyricon 行模型
   * 行内逐字时间缺失时用整行时间兜底，保证时间轴单调可用
   */
  private fun parseLines(linesJson: String): List<RichLyricLine> {
    if (linesJson.isBlank()) return emptyList()
    return try {
      val array = JSONArray(linesJson)
      val result = ArrayList<RichLyricLine>(array.length())
      for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val begin = item.optLong("startTime", 0L)
        val end = item.optLong("endTime", begin)
        val wordsArray = item.optJSONArray("words") ?: JSONArray()
        val words = ArrayList<LyricWord>(wordsArray.length())
        for (j in 0 until wordsArray.length()) {
          val word = wordsArray.optJSONObject(j) ?: continue
          val text = word.optString("word", "")
          if (text.isEmpty()) continue
          val wordBegin = word.optLong("startTime", begin)
          val wordEnd = word.optLong("endTime", wordBegin)
          val safeBegin = wordBegin.coerceAtLeast(0L)
          val safeEnd = wordEnd.coerceAtLeast(safeBegin).let { if (it <= safeBegin) safeBegin + 1 else it }
          words += LyricWord(safeBegin, safeEnd, 0L, text, null)
        }
        val text = buildString { for (word in words) append(word.text) }
        if (text.isEmpty()) continue
        val lineBegin = begin.coerceAtLeast(0L)
        val lineEnd = end.coerceAtLeast(lineBegin).let { if (it <= lineBegin) lineBegin + 1 else it }
        val roman = item.optString("romanLyric", "")
        result +=
          RichLyricLine(
            lineBegin,
            lineEnd,
            0L,
            false,
            null,
            text,
            words,
            roman,
            null,
            item.optString("translatedLyric", ""),
            null,
            roman,
          )
      }
      result
    } catch (error: Throwable) {
      Log.w(TAG, "解析歌词 JSON 失败", error)
      emptyList()
    }
  }
}
