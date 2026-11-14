/**
 * 词幕（Lyricon）状态栏歌词推送
 *
 * 把 SPlayer-Next 已解析的结构化歌词（逐字 / 翻译 / 音译）与播放进度推送给 Lyricon Provider，
 * 由 Lyricon 在系统状态栏渲染。所有调用在失败时静默降级，不影响播放器本体。
 */
import type { LyricLine } from "@shared/types/lyrics";
import { watch } from "vue";
import { isAndroidNative } from "@/services/bridge";
import { useSettingsStore } from "@/stores/settings";
import { useMediaStore } from "@/stores/media";
import { useStatusStore } from "@/stores/status";
import { AndroidLyricon } from "@/plugins/androidLyricon";
import * as playback from "@/services/playback";

/** 是否已成功注册 Provider */
let registered = false;
/** 上次推送的歌曲标识，避免重复推送整首歌词 */
let lastSongKey = "";
/** 上次推送的播放状态 */
let lastPlaying: boolean | null = null;
/** 进度推送节流时间戳 */
let lastPushAt = 0;
/** 当前歌曲是否已成功推送到词幕（连接是异步的，未成功前需要重试） */
let songPushed = false;
/** 上次尝试推歌的时间戳，用于重试节流 */
let lastSongAttemptAt = 0;
/** store 监听清理函数 */
let stopWatchers: (() => void) | null = null;

/** 该功能是否开启（仅 Android 原生 + 设置开关） */
const isEnabled = (): boolean =>
  isAndroidNative && useSettingsStore().system.androidLyric.statusBarEnabled === true;

/** 注册 Provider（幂等） */
const ensureRegistered = async (): Promise<boolean> => {
  if (registered) return true;
  try {
    const res = await AndroidLyricon.enable();
    registered = res.available;
  } catch (error) {
    console.warn("[lyricon] 注册 Provider 失败", error);
    registered = false;
  }
  return registered;
};

/** 清空并重置本地缓存状态 */
const resetCache = (): void => {
  lastSongKey = "";
  songPushed = false;
  lastSongAttemptAt = 0;
  lastPlaying = null;
  lastPushAt = 0;
};

/**
 * 推送当前歌曲与逐字歌词
 * @param force - 强制重推（切歌或歌词变化时）
 */
const pushSong = async (force = false): Promise<void> => {
  if (!isEnabled()) return;
  if (!(await ensureRegistered())) return;

  const media = useMediaStore();
  const track = media.track;
  const lines = media.parsedLyric ?? [];
  if (!track || lines.length === 0) {
    if (lastSongKey) {
      lastSongKey = "";
      await AndroidLyricon.clearSong().catch(() => undefined);
    }
    return;
  }

  const songKey = `${track.id}|${lines.length}|${lines[0]?.startTime ?? 0}`;
  if (!force && songKey === lastSongKey) return;
  lastSongKey = songKey;

  const showTranslation = useSettingsStore().lyric.showTranslation === true;
  const showRoma = useSettingsStore().lyric.showRomanization === true;
  lastSongAttemptAt = Date.now();
  const res = await AndroidLyricon.pushSong({
    linesJson: JSON.stringify(lines as LyricLine[]),
    songId: track.id,
    name: track.title,
    artist: track.artists
      .map((artist) => artist.name)
      .filter(Boolean)
      .join("、"),
    durationMs: Math.round(track.duration ?? 0),
    showTranslation,
    showRoma,
  }).catch((error) => {
    console.warn("[lyricon] 推送歌词失败", error);
    return { ok: false };
  });
  // 只有真正送达才算成功；中心服务连接是异步的，未连上时下次 tick 会重试
  songPushed = res?.ok === true;
  if (!songPushed) lastSongKey = "";
};

/** 推送播放状态与进度（带节流，播放中约 4Hz） */
const pushState = async (force = false): Promise<void> => {
  if (!isEnabled() || !registered) return;
  const status = useStatusStore();
  const playing = status.isPlaying;
  const now = Date.now();
  if (!force && lastPlaying === playing && now - lastPushAt < 250) return;
  lastPlaying = playing;
  lastPushAt = now;
  await AndroidLyricon.setState({
    playing,
    positionMs: Math.round(playback.getCurrentTime()),
  }).catch(() => undefined);
};

/** 监听 store 变化：切歌 / 歌词解析完成时重推整首歌词 */
const installWatchers = (): void => {
  if (stopWatchers) return;
  const media = useMediaStore();
  const stopTrack = watch(
    () => media.track?.id,
    () => {
      resetCache();
      void pushSong(true);
      void pushState(true);
    },
  );
  const stopLyric = watch(
    () => media.parsedLyric,
    () => {
      void pushSong(true);
    },
  );
  stopWatchers = () => {
    stopTrack();
    stopLyric();
  };
};

/** 启动：注册并按需推送一次 */
export const startLyriconPush = async (): Promise<void> => {
  if (!isEnabled()) return;
  const ok = await ensureRegistered();
  if (!ok) return;
  installWatchers();
  resetCache();
  await pushSong(true);
  await pushState(true);
};

/** 关闭：释放 Provider */
export const stopLyriconPush = async (): Promise<void> => {
  if (stopWatchers) {
    stopWatchers();
    stopWatchers = null;
  }
  if (!isAndroidNative) return;
  try {
    await AndroidLyricon.disable();
  } catch (error) {
    console.warn("[lyricon] 释放 Provider 失败", error);
  }
  registered = false;
  resetCache();
};

/** 设置开关变化时重新评估 */
export const syncLyriconSetting = async (): Promise<void> => {
  if (isEnabled()) await startLyriconPush();
  else await stopLyriconPush();
};

/** 播放进度/状态变化的高频入口（内部节流） */
export const tickLyriconPush = (): void => {
  if (!registered) return;
  // 歌词还没成功送达（Provider 刚注册、中心服务还没连上）时按 2s 节流重试
  if (!songPushed && Date.now() - lastSongAttemptAt > 2000) {
    void pushSong(true);
  }
  void pushState(false);
};

/** 切歌或歌词更新后调用：强制重推歌词 */
export const refreshLyriconSong = (): void => {
  if (!registered) return;
  void pushSong(true);
};
