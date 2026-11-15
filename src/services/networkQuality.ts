import { isAndroidNative } from "@/services/bridge";
import { useSettingsStore } from "@/stores/settings";
import type { QualityLevel } from "@/utils/quality";
import AndroidNetwork, { type NetworkTransport } from "@/plugins/androidNetwork";

/**
 * 按网络类型区分在线音质
 *
 * 移动端常见诉求：Wi-Fi 下用无损/Hi-Res，移动数据下降到标准或较高，省流量也避免卡顿。
 * 设置里对应「Wi-Fi 音质」与「移动数据音质」两项，各自可选「跟随通用设置」。
 *
 * 网络类型的获取是异步的（要调原生 ConnectivityManager），但音质解析链路是同步的
 * （audioSource / preloader 都在同步路径里读档位），因此这里维护一份模块级缓存：
 * 异步刷新缓存，同步读取快照；未知网络（仅桌面端 / 原生查询失败）一律回落到通用设置。
 */

/** 未知网络：桌面端、查询失败或尚未完成首次查询 */
const UNKNOWN = "unknown" as const;

type CachedTransport = NetworkTransport | typeof UNKNOWN;

/** 当前网络传输类型缓存 */
let cachedTransport: CachedTransport = UNKNOWN;

/** 是否为计费网络（移动数据）缓存 */
let cachedMetered = false;

/** 上次刷新时间戳（毫秒） */
let lastRefreshAt = 0;

/** 缓存有效期：同一首歌解析期间无需反复查询原生 */
const CACHE_TTL_MS = 5000;

/** 进行中的刷新 Promise（并发调用合并） */
let inflight: Promise<void> | null = null;

/** 当前缓存的网络传输类型（同步读取，可能为 unknown） */
export const currentTransport = (): CachedTransport => cachedTransport;

/**
 * 刷新网络状态缓存
 * @param force - 忽略 TTL 强制刷新
 */
export const refreshNetworkStatus = async (force = false): Promise<void> => {
  if (!isAndroidNative) {
    // 桌面端不存在蜂窝网络，按「非计费」处理，一律走通用设置
    cachedTransport = UNKNOWN;
    cachedMetered = false;
    return;
  }
  const now = Date.now();
  if (!force && now - lastRefreshAt < CACHE_TTL_MS) return;
  if (inflight) return inflight;
  inflight = (async () => {
    try {
      const status = await AndroidNetwork.getStatus();
      cachedTransport = status.type;
      cachedMetered = status.metered;
      lastRefreshAt = Date.now();
    } catch (error) {
      // 查询失败保持上一次结果，不把音质误降档
      console.warn("[networkQuality] 获取网络状态失败:", error);
      lastRefreshAt = Date.now();
    } finally {
      inflight = null;
    }
  })();
  return inflight;
};

/**
 * 该网络类型是否应使用「移动数据音质」
 *
 * 判定依据以计费标记为主、传输类型为辅：
 * - Wi-Fi / 以太网 / 蓝牙共享：明确非移动数据
 * - cellular：明确是移动数据
 * - vpn / other / none：以 metered 标记为准（VPN 分流下底层可能是蜂窝）
 */
const isMobileDataNetwork = (): boolean => {
  switch (cachedTransport) {
    case "wifi":
    case "ethernet":
    case "bluetooth":
      return false;
    case "cellular":
      return true;
    case "vpn":
    case "other":
      return cachedMetered;
    default:
      return false;
  }
};

/**
 * 解析当前应使用的在线音质档位
 *
 * 只有「Wi-Fi 音质」与「移动数据音质」两档，按当前网络二选一。
 * @returns 生效的音质档位
 */
export const effectiveSongLevel = (): QualityLevel => {
  const player = useSettingsStore().player;
  return isMobileDataNetwork() ? player.songLevelMobile : player.songLevelWifi;
};

/**
 * 写入当前网络对应的音质档位
 *
 * 播放页的音质下拉框走这里：改的是「当前网络」那一档。若直接写通用档位，
 * 用户会看到改了设置却不生效。
 * @param level - 目标音质档位
 */
export const setEffectiveSongLevel = (level: QualityLevel): void => {
  const player = useSettingsStore().player;
  if (isMobileDataNetwork()) {
    player.songLevelMobile = level;
  } else {
    player.songLevelWifi = level;
  }
};

/**
 * 启动网络音质监听
 *
 * 首次强制刷新一次，之后在应用回到前台时刷新，保证切换 Wi-Fi / 移动数据后
 * 下一次取流能拿到正确档位（播放中的歌曲不打断，只在下一首或重新解析时生效）。
 */
export const startNetworkQualityWatcher = (): void => {
  void refreshNetworkStatus(true);
  if (typeof document === "undefined") return;
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") void refreshNetworkStatus(true);
  });
};
