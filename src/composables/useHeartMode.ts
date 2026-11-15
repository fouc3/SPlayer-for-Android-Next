import type { Track } from "@shared/types/player";
import { useUserStore } from "@/stores/user";
import { useMediaStore } from "@/stores/media";
import { useStatusStore } from "@/stores/status";
import { fetchHeartModeList } from "@/apis/recommend/netease";
import { toast } from "@/composables/useToast";
import * as player from "@/core/player";

/**
 * 心动模式
 *
 * 以种子歌曲 + 「我喜欢的音乐」歌单调用网易云智能播放接口，
 * 生成推荐队列并进入心动模式。
 */
export const useHeartMode = () => {
  const { t } = useI18n();
  const user = useUserStore();
  const media = useMediaStore();
  const status = useStatusStore();

  /**
   * 进入心动模式
   * @param seed - 指定种子歌曲；缺省时取当前播放的网易云歌曲，再退而取随机红心歌曲 id
   */
  const enterHeartMode = async (seed?: Track): Promise<void> => {
    if (status.heartMode) {
      // 标记可能与实际队列脱节（例如队列被歌单/搜索页换掉但标记没清），
      // 此处不再一味拦截：先让用户明确退出，再继续走进入流程
      player.exitHeartMode();
    }
    if (!user.isLoggedIn) {
      toast.warning(t("player.heartMode.needLogin"));
      return;
    }
    const playlistId = user.likedPlaylistId;
    const current = media.track;
    const likedIds = [...user.likedSongIds];
    const seedId =
      seed?.id ??
      (current?.source === "netease"
        ? current.id
        : likedIds[Math.floor(Math.random() * likedIds.length)]);
    if (!playlistId || !seedId) {
      toast.warning(t("player.heartMode.noSeed"));
      return;
    }
    const loading = toast.loading(t("player.heartMode.loading"), { duration: 0 });
    try {
      const tracks = await fetchHeartModeList(seedId, playlistId);
      if (tracks.length === 0) {
        toast.warning(t("player.heartMode.failed"));
        return;
      }
      await player.playHeartMode(tracks);
      toast.success(t("player.heartMode.entered"));
    } catch (error) {
      console.error("[heartMode] 进入失败:", error);
      toast.warning(t("player.heartMode.failed"));
    } finally {
      loading.close();
    }
  };

  return { enterHeartMode };
};
