import { registerPlugin } from "@capacitor/core";

/** 网络传输类型 */
export type NetworkTransport =
  "wifi" | "cellular" | "ethernet" | "vpn" | "bluetooth" | "other" | "none";

/** 原生网络状态快照 */
export interface NativeNetworkStatus {
  /** 当前生效网络的传输类型 */
  type: NetworkTransport;
  /** 是否为计费网络（移动数据通常为 true） */
  metered: boolean;
  /** 是否有可用网络 */
  connected: boolean;
}

/** 原生网络状态插件（Android） */
interface AndroidNetworkPlugin {
  /** 查询当前网络类型 */
  getStatus: () => Promise<NativeNetworkStatus>;
}

const AndroidNetwork = registerPlugin<AndroidNetworkPlugin>("AndroidNetwork");

export default AndroidNetwork;
