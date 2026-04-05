<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{
  tone: "info" | "warning" | "danger" | "success";
  title: string;
  detail: string;
  progress?: number | null;
  actionLabel?: string;
}>();

const emit = defineEmits<{
  action: [];
}>();

const progressWidth = computed(() => {
  if (props.progress == null) {
    return 0;
  }

  return Math.min(100, Math.max(props.progress, 4));
});
</script>

<template>
  <section :class="['status-banner', `status-banner--${tone}`]">
    <div class="status-banner__content">
      <div>
        <p class="status-banner__title">{{ title }}</p>
        <p class="status-banner__detail">{{ detail }}</p>
      </div>

      <button
        v-if="actionLabel"
        class="status-banner__action"
        type="button"
        @click="emit('action')"
      >
        {{ actionLabel }}
      </button>
    </div>

    <div v-if="progress != null" class="status-banner__progress">
      <div class="status-banner__progress-bar" :style="{ width: `${progressWidth}%` }"></div>
    </div>
  </section>
</template>

<style scoped>
.status-banner {
  display: grid;
  gap: 14px;
  padding: 18px 20px;
  border-radius: 20px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.04);
}

.status-banner--info {
  box-shadow: inset 0 0 0 1px rgba(123, 217, 207, 0.16);
}

.status-banner--warning {
  box-shadow: inset 0 0 0 1px rgba(255, 154, 60, 0.18);
}

.status-banner--danger {
  box-shadow: inset 0 0 0 1px rgba(255, 107, 107, 0.2);
}

.status-banner--success {
  box-shadow: inset 0 0 0 1px rgba(153, 226, 167, 0.18);
}

.status-banner__content {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.status-banner__title {
  margin: 0;
  font-size: 0.92rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--muted);
}

.status-banner__detail {
  margin: 8px 0 0;
  line-height: 1.55;
  color: var(--text);
}

.status-banner__action {
  align-self: center;
  white-space: nowrap;
  padding: 10px 16px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  color: var(--text);
  transition: transform 160ms ease, background 160ms ease;
}

.status-banner__action:hover {
  transform: translateY(-1px);
  background: rgba(255, 255, 255, 0.14);
}

.status-banner__progress {
  overflow: hidden;
  height: 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
}

.status-banner__progress-bar {
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, rgba(255, 154, 60, 0.96), rgba(123, 217, 207, 0.96));
  animation: progress-pulse 1.8s ease-in-out infinite;
}

.app-shell--mobile .status-banner {
  gap: 12px;
  padding: 16px;
  border-radius: 18px;
}

.app-shell--mobile .status-banner__content {
  flex-direction: column;
}

.app-shell--mobile .status-banner__action {
  width: 100%;
}

@keyframes progress-pulse {
  0%,
  100% {
    filter: saturate(0.9);
  }

  50% {
    filter: saturate(1.15);
  }
}

@media (max-width: 640px) {
  .status-banner__content {
    flex-direction: column;
  }

  .status-banner__action {
    width: 100%;
  }
}
</style>
