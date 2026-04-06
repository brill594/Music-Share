<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";

import { formatDurationSeconds } from "@/utils/format";

type PlaybackMode = "stop" | "loop";

const props = defineProps<{
  sourceUrl: string;
  trackTitle: string;
  durationMs: number;
}>();

const audioRef = ref<HTMLAudioElement | null>(null);
const isPlaying = ref(false);
const currentTime = ref(0);
const duration = ref(Math.max(props.durationMs / 1000, 0));
const playbackMode = ref<PlaybackMode>("stop");

watch(
  () => props.sourceUrl,
  () => {
    isPlaying.value = false;
    currentTime.value = 0;
    duration.value = Math.max(props.durationMs / 1000, 0);

    const audio = audioRef.value;
    if (!audio) {
      return;
    }

    audio.pause();
    audio.currentTime = 0;
    audio.loop = playbackMode.value === "loop";
    audio.load();
  },
);

watch(playbackMode, (nextMode) => {
  if (audioRef.value) {
    audioRef.value.loop = nextMode === "loop";
  }
});

const progressPercent = computed(() => {
  if (!duration.value) {
    return 0;
  }

  return Math.min(100, (currentTime.value / duration.value) * 100);
});

const sliderStyle = computed(() => ({
  background: `linear-gradient(90deg, rgba(77, 217, 191, 0.96) 0%, rgba(121, 255, 203, 0.96) ${progressPercent.value}%, rgba(255, 255, 255, 0.12) ${progressPercent.value}%, rgba(255, 255, 255, 0.12) 100%)`,
}));

async function togglePlayback(): Promise<void> {
  const audio = audioRef.value;
  if (!audio) {
    return;
  }

  if (isPlaying.value) {
    audio.pause();
    return;
  }

  try {
    await audio.play();
  } catch {
    isPlaying.value = false;
  }
}

function handleLoadedMetadata(): void {
  const audio = audioRef.value;
  if (!audio) {
    return;
  }

  if (Number.isFinite(audio.duration) && audio.duration > 0) {
    duration.value = audio.duration;
  }
  audio.loop = playbackMode.value === "loop";
}

function handleTimeUpdate(): void {
  const audio = audioRef.value;
  if (!audio) {
    return;
  }

  currentTime.value = audio.currentTime;
}

function handlePlay(): void {
  isPlaying.value = true;
}

function handlePause(): void {
  isPlaying.value = false;
}

function handleEnded(): void {
  isPlaying.value = false;
  currentTime.value = duration.value;
}

function handleSeek(event: Event): void {
  const nextValue = Number((event.target as HTMLInputElement).value);
  currentTime.value = nextValue;
  if (audioRef.value) {
    audioRef.value.currentTime = nextValue;
  }
}

onBeforeUnmount(() => {
  audioRef.value?.pause();
});
</script>

<template>
  <section class="audio-player">
    <audio
      ref="audioRef"
      class="audio-player__native"
      preload="metadata"
      :src="sourceUrl"
      @loadedmetadata="handleLoadedMetadata"
      @timeupdate="handleTimeUpdate"
      @play="handlePlay"
      @pause="handlePause"
      @ended="handleEnded"
    />

    <div class="audio-player__header">
      <div>
        <h2 class="audio-player__title">{{ trackTitle }}</h2>
      </div>

      <div class="audio-player__modes" role="group" aria-label="播放模式">
        <button
          :class="['audio-player__mode-button', { 'audio-player__mode-button--active': playbackMode === 'stop' }]"
          type="button"
          @click="playbackMode = 'stop'"
        >
          播放后停止
        </button>
        <button
          :class="['audio-player__mode-button', { 'audio-player__mode-button--active': playbackMode === 'loop' }]"
          type="button"
          @click="playbackMode = 'loop'"
        >
          单曲循环
        </button>
      </div>
    </div>

    <div class="audio-player__timeline">
      <input
        class="audio-player__range"
        type="range"
        min="0"
        :max="duration || 0"
        step="0.1"
        :value="currentTime"
        :style="sliderStyle"
        aria-label="播放进度"
        @input="handleSeek"
      />
      <div class="audio-player__times">
        <span>{{ formatDurationSeconds(currentTime) }}</span>
        <span>{{ formatDurationSeconds(duration) }}</span>
      </div>
    </div>

    <div class="audio-player__footer">
      <div class="audio-player__controls">
        <button
          class="audio-player__primary audio-player__primary--icon"
          type="button"
          :aria-label="isPlaying ? '暂停' : '播放'"
          @click="void togglePlayback()"
        >
          <span
            v-if="!isPlaying"
            class="audio-player__icon audio-player__icon--play"
            aria-hidden="true"
          ></span>
          <span v-else class="audio-player__icon audio-player__icon--pause" aria-hidden="true">
            <span></span>
            <span></span>
          </span>
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.audio-player {
  display: grid;
  gap: 22px;
  align-content: center;
  padding: 24px;
  border-radius: 24px;
  background:
    linear-gradient(135deg, rgba(121, 255, 203, 0.12), transparent 42%),
    var(--track-page-panel-bg, rgba(255, 255, 255, 0.04));
  box-shadow: inset 0 0 0 1px var(--track-page-panel-border, rgba(255, 255, 255, 0.06));
  min-height: 348px;
}

.audio-player__native {
  display: none;
}

.audio-player__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}

.audio-player__title {
  margin: 0;
  font-size: clamp(1.4rem, 2vw, 1.9rem);
  color: var(--text);
  text-shadow: var(--track-page-heading-shadow, none);
}

.audio-player__modes {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 10px;
}

.audio-player__mode-button {
  padding: 10px 14px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.1);
  color: var(--track-page-label, var(--muted));
  transition: background 160ms ease, color 160ms ease, transform 160ms ease;
}

.audio-player__mode-button:hover {
  transform: translateY(-1px);
}

.audio-player__mode-button--active {
  background: rgba(121, 255, 203, 0.18);
  color: var(--text);
}

.audio-player__timeline {
  display: grid;
  gap: 12px;
}

.audio-player__range {
  appearance: none;
  width: 100%;
  height: 10px;
  border-radius: 999px;
  outline: none;
  cursor: pointer;
}

.audio-player__range::-webkit-slider-thumb {
  appearance: none;
  width: 20px;
  height: 20px;
  border: 0;
  border-radius: 50%;
  background: #e8fff8;
  box-shadow: 0 0 0 5px rgba(121, 255, 203, 0.22);
}

.audio-player__range::-moz-range-thumb {
  width: 20px;
  height: 20px;
  border: 0;
  border-radius: 50%;
  background: #e8fff8;
  box-shadow: 0 0 0 5px rgba(121, 255, 203, 0.22);
}

.audio-player__times {
  display: flex;
  justify-content: space-between;
  color: var(--text);
  font-size: 0.92rem;
  text-shadow: var(--track-page-heading-shadow, none);
}

.audio-player__footer {
  display: grid;
  gap: 16px;
}

.audio-player__controls {
  display: flex;
  justify-content: center;
  gap: 12px;
}

.audio-player__primary {
  min-width: 110px;
  padding: 12px 26px;
  border-radius: 999px;
  color: var(--text);
}

.audio-player__primary {
  background: linear-gradient(120deg, rgba(121, 255, 203, 0.90), rgba(77, 217, 191, 0.90));
  color: #0a1f18;
}

.audio-player__primary--icon {
  width: 92px;
  min-width: 92px;
  height: 92px;
  padding: 0;
  display: inline-grid;
  place-items: center;
  border-radius: 50%;
  box-shadow: 0 16px 32px rgba(77, 217, 191, 0.16);
}

.audio-player__icon {
  display: inline-block;
}

.audio-player__icon--play {
  width: 0;
  height: 0;
  margin-left: 6px;
  border-top: 15px solid transparent;
  border-bottom: 15px solid transparent;
  border-left: 24px solid #0a1f18;
}

.audio-player__icon--pause {
  display: inline-flex;
  gap: 8px;
}

.audio-player__icon--pause span {
  width: 9px;
  height: 30px;
  border-radius: 999px;
  background: #0a1f18;
}

.app-shell--mobile .audio-player {
  gap: 18px;
  min-height: auto;
  padding: 20px;
  border-radius: 20px;
}

.app-shell--mobile .audio-player__header {
  flex-direction: column;
  gap: 14px;
}

.app-shell--mobile .audio-player__title {
  font-size: clamp(1.24rem, 5vw, 1.65rem);
}

.app-shell--mobile .audio-player__modes {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  width: 100%;
}

.app-shell--mobile .audio-player__mode-button {
  width: 100%;
  padding: 12px 14px;
}

.app-shell--mobile .audio-player__controls {
  display: flex;
  justify-content: stretch;
}

.app-shell--mobile .audio-player__primary {
  min-width: 0;
  width: 84px;
  height: 84px;
  color: #0a1f18;
}

.app-shell--desktop .audio-player {
  padding-block: 30px;
}

.app-shell--desktop .audio-player__timeline,
.app-shell--desktop .audio-player__footer {
  width: min(100%, 460px);
  justify-self: center;
}

@media (max-width: 720px) {
  .audio-player__header {
    flex-direction: column;
  }
}
</style>
