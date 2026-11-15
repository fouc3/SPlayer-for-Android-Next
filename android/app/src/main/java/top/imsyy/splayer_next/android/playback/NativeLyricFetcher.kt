package top.imsyy.splayer_next.android.playback

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 原生歌词兜底拉取器。
 *
 * 应用切到后台后 WebView 的 JS 可能被系统冻结（播放链路的原生自治切歌就是为此设计的），
 * 此时 JS 无法为新歌取歌词，灵动岛会停留在上一首。这里由原生直接走嵌入式 API 取一次行级歌词，
 * 解析成灵动岛需要的 JSON 后推送；JS 恢复执行后会推更精确的逐字歌词并覆盖本结果。
 *
 * 只处理行级时间轴（含 YRC 的行级抽取），不解析逐字时间，够灵动岛在后台显示。
 */
internal object NativeLyricFetcher {
  private const val TAG = "NativeLyricFetcher"
  private const val CONNECT_TIMEOUT_MS = 6000
  private const val READ_TIMEOUT_MS = 8000

  /** 最后一行没有下一行时使用的展示时长 */
  private const val TAIL_LINE_MS = 6000L

  /**
   * 拉取并转换为灵动岛歌词 JSON
   * @param apiBaseUrl - 形如 http://127.0.0.1:PORT/api/netease
   * @param cookie - 账号 Cookie
   * @param songId - 歌曲 id（网易云数字 id）
   * @param platform - 平台标识
   * @returns 灵动岛可直接使用的歌词 JSON；失败返回 null
   */
  fun fetchIslandLyricJson(
    apiBaseUrl: String,
    cookie: String,
    songId: Long,
    platform: String,
  ): String? {
    if (apiBaseUrl.isEmpty() || songId <= 0) return null
    val origin = apiBaseUrl.substringBefore("/api/")
    if (origin.isEmpty()) return null
    val body = buildBody(platform, songId) ?: return null
    val payload = postJson("$origin/api/lyrics/matchById", cookie, body) ?: return null
    return try {
      val root = JSONObject(payload)
      if (!root.optBoolean("ok", false)) return null
      val data = root.optJSONObject("data") ?: return null
      val content = data.optString("content", "")
      if (content.isBlank()) return null
      val translation = data.optString("translation", "")
      buildIslandJson(content, translation)
    } catch (error: Exception) {
      Log.w(TAG, "解析歌词响应失败", error)
      null
    }
  }

  /** 组装请求体：网易云按 id 精确匹配，其它平台走 id 匹配 */
  private fun buildBody(
    platform: String,
    songId: Long,
  ): String? = try {
    JSONObject()
      .put("platform", platform)
      .put("id", songId.toString())
      .toString()
  } catch (error: Exception) {
    null
  }

  /** POST JSON 并返回响应体文本 */
  private fun postJson(
    url: String,
    cookie: String,
    body: String,
  ): String? {
    var connection: HttpURLConnection? = null
    return try {
      connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        setRequestProperty("Accept", "application/json")
        if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
      }
      connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
      if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
      connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    } catch (error: Exception) {
      Log.w(TAG, "请求歌词失败", error)
      null
    } finally {
      connection?.disconnect()
    }
  }

  /**
   * 把 LRC / YRC 文本转换为灵动岛歌词 JSON
   *
   * 支持的输入：
   * - LRC：`[mm:ss.xxx]文本`
   * - YRC：`[起始毫秒,时长](逐字时间,时长,标志)文本…`，这里只取行级文本，逐字信息丢弃
   */
  private fun buildIslandJson(
    content: String,
    translation: String,
  ): String? {
    val times = ArrayList<Long>()
    val texts = ArrayList<String>()
    for (raw in content.lineSequence()) {
      val (time, text) = parseLine(raw) ?: continue
      if (text.isBlank()) continue
      times.add(time)
      texts.add(text)
    }
    if (times.isEmpty()) return null
    val transMap = parseTranslation(translation)
    val array = JSONArray()
    for (i in times.indices) {
      val start = times[i]
      val end = if (i + 1 < times.size) times[i + 1] else start + TAIL_LINE_MS
      val text = texts[i]
      val line = JSONObject()
      line.put("startTime", start)
      line.put("endTime", end)
      line.put("translatedLyric", transMap[start] ?: "")
      line.put("romanLyric", "")
      line.put("isBG", false)
      line.put("isDuet", false)
      val words = JSONArray()
      words.put(
        JSONObject()
          .put("word", text)
          .put("startTime", start)
          .put("endTime", end),
      )
      line.put("words", words)
      array.put(line)
    }
    return array.toString()
  }

  /** 解析单行，返回 (起始毫秒, 文本)；非歌词行返回 null */
  private fun parseLine(raw: String): Pair<Long, String>? {
    val line = raw.trim()
    if (!line.startsWith("[")) return null
    val close = line.indexOf(']')
    if (close <= 0) return null
    val header = line.substring(1, close)
    val rest = line.substring(close + 1)
    // YRC：起始毫秒,时长
    if (!header.contains(":")) {
      val start = header.substringBefore(',').toLongOrNull() ?: return null
      return start to stripWordTags(rest)
    }
    // LRC：[mm:ss] / [mm:ss.xxx] / [mm:ss:xxx] / [hh:mm:ss.xxx]
    val parts = header.split(':')
    val ms =
      when (parts.size) {
        2 -> {
          val minutes = parts[0].toLongOrNull() ?: return null
          val seconds = parts[1].toDoubleOrNull() ?: return null
          minutes * 60_000L + (seconds * 1000).toLong()
        }
        3 -> {
          val hours = parts[0].toLongOrNull() ?: return null
          val minutes = parts[1].toLongOrNull() ?: return null
          val seconds = parts[2].toDoubleOrNull() ?: return null
          hours * 3_600_000L + minutes * 60_000L + (seconds * 1000).toLong()
        }
        else -> return null
      }
    return ms to rest.trim()
  }

  /** 去掉 YRC 的逐字时间标记 `(a,b,c)` */
  private fun stripWordTags(text: String): String {
    val out = StringBuilder(text.length)
    var depth = 0
    for (ch in text) {
      when (ch) {
        '(' -> depth++
        ')' -> if (depth > 0) depth--
        else -> if (depth == 0) out.append(ch)
      }
    }
    return out.toString().trim()
  }

  /** 解析翻译歌词：起始毫秒 → 文本 */
  private fun parseTranslation(translation: String): Map<Long, String> {
    if (translation.isBlank()) return emptyMap()
    val map = HashMap<Long, String>()
    for (raw in translation.lineSequence()) {
      val (time, text) = parseLine(raw) ?: continue
      if (text.isNotBlank()) map[time] = text
    }
    return map
  }
}
