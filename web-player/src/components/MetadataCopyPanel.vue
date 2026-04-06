<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";

import { copyTextToClipboard } from "@/utils/clipboard";

const props = defineProps<{
  title: string;
  artist: string;
  album: string;
}>();

const includeTitle = ref(true);
const includeArtist = ref(false);
const includeAlbum = ref(false);
const feedback = ref<"idle" | "success" | "error">("idle");
let feedbackTimer: number | null = null;

function resetSelection(): void {
  includeTitle.value = true;
  includeArtist.value = Boolean(props.artist);
  includeAlbum.value = false;
  feedback.value = "idle";
}

watch(
  () => [props.title, props.artist, props.album],
  () => {
    resetSelection();
  },
  { immediate: true },
);

const selectedLines = computed(() => {
  const lines: string[] = [];
  if (includeTitle.value && props.title) {
    lines.push(props.title);
  }
  if (includeArtist.value && props.artist) {
    lines.push(props.artist);
  }
  if (includeAlbum.value && props.album) {
    lines.push(props.album);
  }
  return lines;
});

const previewText = computed(() => selectedLines.value.join("\n"));

async function handleCopy(): Promise<void> {
  if (!previewText.value) {
    return;
  }

  try {
    await copyTextToClipboard(previewText.value);
    feedback.value = "success";
  } catch {
    feedback.value = "error";
  }

  if (feedbackTimer) {
    window.clearTimeout(feedbackTimer);
  }
  feedbackTimer = window.setTimeout(() => {
    feedback.value = "idle";
  }, 1800);
}

onBeforeUnmount(() => {
  if (feedbackTimer) {
    window.clearTimeout(feedbackTimer);
  }
});
</script>

<template>
  <section class="copy-panel">
    <div class="copy-panel__header">
      <div>
        <h2 class="copy-panel__title">歌曲信息</h2>
      </div>
      <button class="copy-panel__button" type="button" :disabled="!previewText" @click="void handleCopy()">
        {{
          feedback === "success"
            ? "已复制"
            : feedback === "error"
              ? "复制失败"
              : "复制已选文本"
        }}
      </button>
    </div>

    <div class="copy-panel__options">
      <label class="copy-panel__checkbox">
        <input v-model="includeTitle" type="checkbox" />
        <span>歌曲名</span>
      </label>
      <label class="copy-panel__checkbox" :class="{ 'copy-panel__checkbox--disabled': !artist }">
        <input v-model="includeArtist" type="checkbox" :disabled="!artist" />
        <span>艺术家</span>
      </label>
      <label class="copy-panel__checkbox" :class="{ 'copy-panel__checkbox--disabled': !album }">
        <input v-model="includeAlbum" type="checkbox" :disabled="!album" />
        <span>专辑</span>
      </label>
    </div>

    <div class="copy-panel__preview">
      <p class="copy-panel__preview-label">预览</p>
      <pre class="copy-panel__preview-text">{{ previewText || "至少选择一个字段后才能复制。" }}</pre>
    </div>
  </section>
</template>

<style scoped>
.copy-panel {
  display: grid;
  gap: 20px;
  padding: 24px;
  border-radius: 24px;
  background:
    linear-gradient(145deg, rgba(121, 255, 203, 0.10), transparent 38%),
    rgba(255, 255, 255, 0.04);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.06);
}

.copy-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.copy-panel__title {
  margin: 0;
  font-size: clamp(1.35rem, 2vw, 1.8rem);
}

.copy-panel__button {
  padding: 12px 18px;
  border-radius: 999px;
  background: rgba(121, 255, 203, 0.16);
  color: var(--text);
}

.copy-panel__button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.copy-panel__options {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.copy-panel__checkbox {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.05);
  color: var(--text);
}

.copy-panel__checkbox input {
  accent-color: var(--accent);
}

.copy-panel__checkbox--disabled {
  opacity: 0.48;
}

.copy-panel__preview {
  padding: 18px 20px;
  border-radius: 20px;
  background: rgba(8, 16, 23, 0.42);
}

.copy-panel__preview-label {
  margin: 0 0 10px;
  color: var(--muted);
  font-size: 0.84rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.copy-panel__preview-text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: var(--font-body);
  line-height: 1.7;
  color: var(--text);
}

.app-shell--mobile .copy-panel {
  gap: 18px;
  padding: 20px;
  border-radius: 20px;
}

.app-shell--mobile .copy-panel__header {
  flex-direction: column;
}

.app-shell--mobile .copy-panel__button {
  width: 100%;
}

.app-shell--mobile .copy-panel__options {
  display: grid;
  grid-template-columns: 1fr;
}

.app-shell--mobile .copy-panel__checkbox {
  justify-content: space-between;
}

@media (max-width: 640px) {
  .copy-panel__header {
    flex-direction: column;
  }

  .copy-panel__button {
    width: 100%;
  }
}
</style>
