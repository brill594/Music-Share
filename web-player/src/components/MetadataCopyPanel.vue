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

type MetadataItem = {
  key: "title" | "artist" | "album";
  label: string;
  value: string;
  checked: typeof includeTitle;
  disabled: boolean;
};

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

const selectedText = computed(() => selectedLines.value.join("\n"));

const metadataItems = computed<MetadataItem[]>(() => [
  {
    key: "title",
    label: "歌曲名",
    value: props.title || "未标注歌曲名",
    checked: includeTitle,
    disabled: !props.title,
  },
  {
    key: "artist",
    label: "艺术家",
    value: props.artist || "未标注艺术家",
    checked: includeArtist,
    disabled: !props.artist,
  },
  {
    key: "album",
    label: "专辑",
    value: props.album || "未标注专辑",
    checked: includeAlbum,
    disabled: !props.album,
  },
]);

async function handleCopy(): Promise<void> {
  if (!selectedText.value) {
    return;
  }

  try {
    await copyTextToClipboard(selectedText.value);
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

function handleToggle(item: MetadataItem): void {
  if (item.disabled) {
    return;
  }

  window.setTimeout(() => {
    void handleCopy();
  }, 0);
}
</script>

<template>
  <section class="copy-panel">
    <div class="copy-panel__header">
      <div>
        <h2 class="copy-panel__title">歌曲信息</h2>
      </div>
      <button class="copy-panel__button" type="button" :disabled="!selectedText" @click="void handleCopy()">
        {{
          feedback === "success"
            ? "已复制"
            : feedback === "error"
              ? "复制失败"
              : "复制勾选内容"
        }}
      </button>
    </div>

    <div class="copy-panel__list" role="group" aria-label="可复制的歌曲字段">
      <label
        v-for="item in metadataItems"
        :key="item.key"
        class="copy-panel__item"
        :class="{ 'copy-panel__item--disabled': item.disabled }"
      >
        <span class="copy-panel__item-main">
          <input
            v-model="item.checked.value"
            class="copy-panel__item-checkbox"
            type="checkbox"
            :disabled="item.disabled"
            @change="handleToggle(item)"
          />
          <span class="copy-panel__item-label">{{ item.label }}</span>
        </span>
        <span class="copy-panel__item-value" :title="item.value">
          <span class="copy-panel__item-value-marquee">{{ item.value }}</span>
        </span>
      </label>
    </div>
  </section>
</template>

<style scoped>
.copy-panel {
  display: grid;
  gap: 18px;
  padding: 24px;
  border-radius: 24px;
  background:
    linear-gradient(145deg, rgba(121, 255, 203, 0.10), transparent 38%),
    rgba(255, 255, 255, 0.04);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.06);
  min-height: 348px;
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

.copy-panel__list {
  display: grid;
  gap: 12px;
  align-content: start;
}

.copy-panel__item {
  display: flex;
  align-items: flex-start;
  justify-content: flex-start;
  gap: 12px;
  min-height: 64px;
  padding: 14px 16px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.05);
  color: var(--text);
}

.copy-panel__item-main {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 132px;
  flex: 0 0 132px;
  padding-top: 2px;
}

.copy-panel__item-checkbox {
  accent-color: var(--accent);
}

.copy-panel__item-label {
  color: var(--muted);
  white-space: nowrap;
}

.copy-panel__item-value {
  min-width: 0;
  flex: 1 1 auto;
  display: block;
  max-width: min(100%, 280px);
  text-align: left;
  line-height: 1.45;
  user-select: text;
  overflow-x: auto;
  overflow-y: hidden;
  white-space: nowrap;
  scrollbar-width: none;
  align-self: flex-start;
  padding-top: 2px;
}

.copy-panel__item-value-marquee {
  display: inline-block;
  min-width: 100%;
  white-space: nowrap;
  cursor: text;
}

.copy-panel__item-value::-webkit-scrollbar {
  display: none;
}

.copy-panel__item--disabled {
  opacity: 0.52;
}

.app-shell--mobile .copy-panel {
  gap: 18px;
  min-height: auto;
  padding: 20px;
  border-radius: 20px;
}

.app-shell--mobile .copy-panel__header {
  flex-direction: column;
}

.app-shell--mobile .copy-panel__button {
  width: 100%;
}

.app-shell--mobile .copy-panel__item {
  align-items: flex-start;
  flex-direction: column;
}

.app-shell--mobile .copy-panel__item-main {
  min-width: 0;
  flex: 0 0 auto;
}

.app-shell--mobile .copy-panel__item-value {
  width: 100%;
  max-width: 100%;
  text-align: left;
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
