<script setup lang="ts">
import { computed, ref, watch } from "vue";

import placeholderCoverUrl from "@/assets/cover-placeholder.svg";
import { formatDateTime, formatRelativeExpiry } from "@/utils/format";

const props = defineProps<{
  title: string;
  artist: string;
  album: string;
  durationMs: number;
  audioMime: string;
  coverUrl: string | null;
  expiresAt: string;
}>();

const coverFailed = ref(false);

watch(
  () => props.coverUrl,
  () => {
    coverFailed.value = false;
  },
);

const resolvedCoverUrl = computed(() => {
  if (!props.coverUrl || coverFailed.value) {
    return placeholderCoverUrl;
  }
  return props.coverUrl;
});

const artistLabel = computed(() => props.artist || "未知艺术家");
const albumLabel = computed(() => props.album || "未标注专辑");
const expiryHint = computed(() => formatRelativeExpiry(props.expiresAt));
</script>

<template>
  <section class="track-hero">
    <div class="track-hero__cover-panel">
      <img
        class="track-hero__cover"
        :src="resolvedCoverUrl"
        :alt="`${title} cover`"
        @error="coverFailed = true"
      />
    </div>

    <div class="track-hero__body">
      <div class="track-hero__summary">
        <h1 class="track-hero__title">{{ title }}</h1>
        <p class="track-hero__artist">{{ artistLabel }}</p>
        <p class="track-hero__album">{{ albumLabel }}</p>
      </div>

      <div class="track-hero__meta-grid">
        <div>
          <p class="track-hero__label">过期提醒</p>
          <p class="track-hero__value">{{ expiryHint }}</p>
        </div>
        <div>
          <p class="track-hero__label">失效时间</p>
          <p class="track-hero__value">{{ formatDateTime(expiresAt) }}</p>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.track-hero {
  display: grid;
  grid-template-columns: minmax(180px, 280px) minmax(0, 1fr);
  gap: clamp(22px, 4vw, 36px);
  align-items: center;
}

.track-hero__cover-panel {
  position: relative;
  overflow: hidden;
  padding: 14px;
  border-radius: 28px;
  background:
    linear-gradient(145deg, rgba(121, 255, 203, 0.22), rgba(77, 217, 191, 0.14)),
    rgba(255, 255, 255, 0.04);
  box-shadow:
    inset 0 0 0 1px rgba(255, 255, 255, 0.08),
    0 28px 60px rgba(0, 0, 0, 0.28);
}

.track-hero__cover {
  display: block;
  width: 100%;
  aspect-ratio: 1;
  object-fit: cover;
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.08);
}

.track-hero__body {
  display: grid;
  gap: 18px;
  min-width: 0;
}

.track-hero__summary {
  display: grid;
  gap: 12px;
  min-width: 0;
}

.track-hero__title {
  margin: 0;
  font-family: var(--font-display);
  font-size: clamp(2.2rem, 5vw, 4.6rem);
  line-height: 0.95;
  letter-spacing: -0.04em;
  text-wrap: balance;
}

.track-hero__artist {
  margin: 0;
  font-size: clamp(1.15rem, 2vw, 1.45rem);
  color: var(--text);
}

.track-hero__album {
  margin: 0;
  color: var(--muted);
  font-size: 1rem;
}

.track-hero__meta-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.track-hero__meta-grid > div {
  padding: 16px 18px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.04);
}

.track-hero__label {
  margin: 0;
  color: var(--muted);
  font-size: 0.84rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.track-hero__value {
  margin: 8px 0 0;
  color: var(--text);
  font-size: 1rem;
}

.app-shell--desktop .track-hero {
  grid-template-columns: minmax(220px, 320px) minmax(0, 1fr);
  align-items: start;
  gap: clamp(24px, 3vw, 40px);
}

.app-shell--desktop .track-hero__cover-panel {
  max-width: 100%;
}

.app-shell--desktop .track-hero__body {
  gap: 22px;
  min-height: 100%;
}

.app-shell--desktop .track-hero__summary {
  gap: 14px;
}

.app-shell--desktop .track-hero__meta-grid {
  margin-top: auto;
}

.app-shell--mobile .track-hero {
  grid-template-columns: 1fr;
  gap: 18px;
}

.app-shell--mobile .track-hero__cover-panel {
  max-width: none;
  padding: 12px;
  border-radius: 24px;
}

.app-shell--mobile .track-hero__body {
  gap: 12px;
}

.app-shell--mobile .track-hero__summary {
  gap: 10px;
}

.app-shell--mobile .track-hero__title {
  font-size: clamp(2rem, 8vw, 3rem);
}

.app-shell--mobile .track-hero__meta-grid {
  grid-template-columns: 1fr;
  gap: 12px;
}

@media (max-width: 840px) {
  .track-hero {
    grid-template-columns: 1fr;
  }

  .track-hero__cover-panel {
    max-width: 380px;
  }
}

@media (max-width: 560px) {
  .track-hero__meta-grid {
    grid-template-columns: 1fr;
  }
}
</style>
