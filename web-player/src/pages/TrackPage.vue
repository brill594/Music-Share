<script setup lang="ts">
import { computed, onBeforeUnmount, watch } from "vue";
import { storeToRefs } from "pinia";
import { useRoute, useRouter } from "vue-router";

import AudioPlayer from "@/components/AudioPlayer.vue";
import MetadataCopyPanel from "@/components/MetadataCopyPanel.vue";
import StatusBanner from "@/components/StatusBanner.vue";
import TrackHero from "@/components/TrackHero.vue";
import { useTrackStore } from "@/stores/track";
import { formatBytes, formatDateTime } from "@/utils/format";

const route = useRoute();
const router = useRouter();
const trackStore = useTrackStore();

const { audioUrl, downloadProgress, errorMessage, phase, track, viewState } = storeToRefs(trackStore);

const currentShareCode = computed(() => {
  const rawValue = route.params.shareCode;
  return typeof rawValue === "string" ? rawValue.trim() : "";
});

const pageBackdropStyle = computed(() => {
  const backgroundUrl = track.value?.background_url ?? track.value?.cover_url ?? "";
  if (!backgroundUrl) {
    return {};
  }

  return {
    backgroundImage: `url("${backgroundUrl}")`,
  };
});

const trackPageSurfaceStyle = computed(() => {
  const backgroundUrl = track.value?.background_url ?? "";
  if (!backgroundUrl) {
    return {};
  }

  return {
    "--track-page-custom-bg": `url("${backgroundUrl}")`,
  };
});

const audioDownloadLabel = computed(() => {
  const { loaded, total } = downloadProgress.value;
  if (total) {
    return `已下载 ${formatBytes(loaded)} / ${formatBytes(total)}。`;
  }
  if (loaded > 0) {
    return `已下载 ${formatBytes(loaded)}。`;
  }
  return "正在准备音频文件。";
});

const bannerTitle = computed(() => {
  if (viewState.value === "error") {
    return "加载失败";
  }
  if (phase.value === "metadata") {
    return "正在读取分享信息";
  }
  if (phase.value === "audio") {
    return "正在下载音频";
  }
  return "临时分享链接";
});

const bannerDetail = computed(() => {
  if (viewState.value === "error") {
    return errorMessage.value || "网络请求失败，请稍后重试。";
  }
  if (phase.value === "metadata") {
    return "正在加载分享内容。";
  }
  if (phase.value === "audio") {
    return audioDownloadLabel.value;
  }
  if (track.value) {
    return `链接将于 ${formatDateTime(track.value.expires_at)} 失效。`;
  }
  return "正在准备试听页面。";
});

const bannerTone = computed<"info" | "warning" | "danger" | "success">(() => {
  if (viewState.value === "error") {
    return "danger";
  }
  if (viewState.value === "ready") {
    return "warning";
  }
  return "info";
});

async function openShare(shareCode: string): Promise<void> {
  const result = await trackStore.load(shareCode);

  if (result === "expired") {
    await router.replace({
      name: "expired",
      query: { code: shareCode },
    });
    return;
  }

  if (result === "not_found") {
    await router.replace({
      name: "not-found",
      query: { code: shareCode },
    });
  }
}

async function retryCurrentShare(): Promise<void> {
  const shareCode = currentShareCode.value || trackStore.currentCode;
  if (!shareCode) {
    return;
  }

  await openShare(shareCode);
}

watch(
  currentShareCode,
  (nextShareCode) => {
    if (!nextShareCode) {
      void router.replace({ name: "not-found" });
      return;
    }

    void openShare(nextShareCode);
  },
  { immediate: true },
);

onBeforeUnmount(() => {
  trackStore.teardown();
});
</script>

<template>
  <div class="page-shell">
    <div class="page-shell__cover" :style="pageBackdropStyle"></div>
    <div class="page-shell__veil"></div>
    <div class="page-shell__grain"></div>

    <main class="page-shell__content">
      <div class="track-page-surface" :style="trackPageSurfaceStyle">
        <section class="page-card page-card--wide track-page">
          <StatusBanner
            :tone="bannerTone"
            :title="bannerTitle"
            :detail="bannerDetail"
            :progress="phase === 'audio' ? downloadProgress.percent : null"
            :action-label="viewState === 'error' ? '重新加载' : undefined"
            @action="void retryCurrentShare()"
          />

          <div v-if="track" class="track-page__stack">
            <div class="track-page__content-grid">
              <div class="track-page__hero-slot">
                <TrackHero
                  :title="track.title"
                  :artist="track.artist"
                  :album="track.album"
                  :duration-ms="track.duration_ms"
                  :audio-mime="track.audio_mime"
                  :cover-url="track.cover_url"
                  :expires-at="track.expires_at"
                />
              </div>

              <div class="track-page__player-slot">
                <div v-if="audioUrl" class="track-page__section">
                  <AudioPlayer
                    :source-url="audioUrl"
                    :track-title="track.title"
                    :duration-ms="track.duration_ms"
                  />
                </div>

                <section v-else class="track-page__pending-audio">
                  <p class="track-page__pending-title">播放器即将就绪</p>
                  <p class="track-page__pending-detail">
                    正在准备音频，完成后即可播放。
                  </p>
                </section>
              </div>

              <div class="track-page__copy-slot">
                <MetadataCopyPanel
                  :title="track.title"
                  :artist="track.artist"
                  :album="track.album"
                />
              </div>
            </div>
          </div>

          <section v-else class="track-page__placeholder">
            <div class="track-page__placeholder-cover"></div>
            <div class="track-page__placeholder-lines">
              <span></span>
              <span></span>
              <span></span>
            </div>
          </section>
        </section>
      </div>
    </main>
  </div>
</template>

<style scoped>
.track-page-surface {
  position: relative;
  isolation: isolate;
  overflow: hidden;
  width: 100%;
  border-radius: 30px;
}

.track-page-surface::before {
  content: "";
  position: absolute;
  inset: 0;
  z-index: 0;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: inherit;
  background-image:
    var(--track-page-custom-bg, none),
    radial-gradient(circle at top left, rgba(235, 248, 245, 0.18), transparent 34%),
    linear-gradient(140deg, rgba(244, 248, 246, 0.16), rgba(216, 228, 231, 0.08));
  background-position: center;
  background-repeat: no-repeat;
  background-size: cover;
  opacity: 0.96;
  backdrop-filter: blur(34px) saturate(1.15);
  -webkit-backdrop-filter: blur(34px) saturate(1.15);
  box-shadow:
    0 32px 90px rgba(0, 0, 0, 0.28),
    inset 0 0 0 1px rgba(255, 255, 255, 0.05);
}

.track-page-surface::after {
  content: "";
  position: absolute;
  inset: 0;
  z-index: 0;
  border-radius: inherit;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.10), rgba(255, 255, 255, 0.04) 28%, rgba(9, 15, 22, 0.10) 100%),
    rgba(18, 28, 38, 0.34);
  pointer-events: none;
}

.track-page {
  position: relative;
  z-index: 1;
  display: grid;
  gap: 22px;
  background: transparent;
  border-color: transparent;
  box-shadow: none;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.track-page__stack {
  display: grid;
  gap: 22px;
}

.track-page__content-grid {
  display: grid;
  gap: 22px;
}

.track-page__hero-slot,
.track-page__player-slot,
.track-page__copy-slot {
  min-width: 0;
}

.track-page__section {
  display: grid;
}

.track-page__pending-audio {
  padding: 24px;
  border-radius: 24px;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.06), transparent 55%),
    rgba(255, 255, 255, 0.04);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.06);
}

.track-page__pending-title {
  margin: 0;
  font-size: 1.2rem;
  color: var(--text);
}

.track-page__pending-detail {
  margin: 10px 0 0;
  color: var(--muted);
  line-height: 1.7;
}

.app-shell--desktop .track-page__content-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
  gap: clamp(24px, 3vw, 40px);
}

.app-shell--desktop .track-page__hero-slot {
  grid-column: 1 / -1;
  grid-row: 1;
}

.app-shell--desktop .track-page__player-slot {
  grid-column: 1;
  grid-row: 2;
}

.app-shell--desktop .track-page__copy-slot {
  grid-column: 2;
  grid-row: 2;
}

@media (max-width: 1120px) {
  .app-shell--desktop .track-page__content-grid {
    grid-template-columns: 1fr;
  }

  .app-shell--desktop .track-page__hero-slot,
  .app-shell--desktop .track-page__player-slot,
  .app-shell--desktop .track-page__copy-slot {
    grid-column: 1;
  }

  .app-shell--desktop .track-page__copy-slot {
    grid-row: 3;
  }
}

.app-shell--mobile .track-page__content-grid {
  grid-template-columns: 1fr;
}

.app-shell--mobile .track-page__stack {
  gap: 18px;
}

.app-shell--mobile .track-page__pending-audio {
  padding: 20px;
  border-radius: 20px;
}

.app-shell--mobile .track-page-surface {
  border-radius: 22px;
}

.track-page__placeholder {
  display: grid;
  gap: 20px;
  justify-items: center;
  padding: clamp(28px, 8vw, 60px) 10px;
}

.track-page__placeholder-cover {
  width: min(100%, 320px);
  aspect-ratio: 1;
  border-radius: 28px;
  background: linear-gradient(135deg, rgba(121, 255, 203, 0.2), rgba(77, 217, 191, 0.1));
  animation: placeholder-pulse 1.6s ease-in-out infinite;
}

.track-page__placeholder-lines {
  display: grid;
  gap: 12px;
  width: min(100%, 520px);
}

.track-page__placeholder-lines span {
  display: block;
  height: 16px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  animation: placeholder-pulse 1.6s ease-in-out infinite;
}

.track-page__placeholder-lines span:nth-child(2) {
  width: 72%;
}

.track-page__placeholder-lines span:nth-child(3) {
  width: 54%;
}

@keyframes placeholder-pulse {
  0%,
  100% {
    opacity: 0.44;
  }

  50% {
    opacity: 0.88;
  }
}
</style>
