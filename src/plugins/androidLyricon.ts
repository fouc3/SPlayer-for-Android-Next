import { registerPlugin } from "@capacitor/core";

/** 词幕（Lyricon）Provider 插件接口 */
export interface AndroidLyriconPlugin {
  /** 注册 Provider */
  enable(): Promise<{ available: boolean }>;
  /** 释放 Provider */
  disable(): Promise<void>;
  /** 是否就绪 */
  isAvailable(): Promise<{ available: boolean }>;
  /** 推送歌曲与逐字歌词 */
  pushSong(options: {
    linesJson: string;
    songId: string;
    name: string;
    artist: string;
    durationMs: number;
    showTranslation: boolean;
    showRoma: boolean;
  }): Promise<{ ok: boolean; reason?: string }>;
  /** 同步播放状态与进度 */
  setState(options: { playing: boolean; positionMs: number }): Promise<{ ok: boolean }>;
  /** 清空当前歌曲 */
  clearSong(): Promise<void>;
}

export const AndroidLyricon = registerPlugin<AndroidLyriconPlugin>("AndroidLyricon");
