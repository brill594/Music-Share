<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";

import type { DeviceMode } from "@/types/device";
import { applyDeviceMode, detectDeviceMode } from "@/utils/device";

const deviceMode = ref<DeviceMode>("desktop");
let orientationMedia: MediaQueryList | null = null;
type LegacyMediaQueryList = MediaQueryList & {
  addListener?: (listener: (event: MediaQueryListEvent) => void) => void;
  removeListener?: (listener: (event: MediaQueryListEvent) => void) => void;
};

function syncDeviceMode(): void {
  deviceMode.value = detectDeviceMode();
  applyDeviceMode(deviceMode.value);
}

function handleOrientationChange(): void {
  syncDeviceMode();
}

onMounted(() => {
  syncDeviceMode();
  window.addEventListener("resize", syncDeviceMode);
  orientationMedia = window.matchMedia("(orientation: portrait)");
  if (typeof orientationMedia.addEventListener === "function") {
    orientationMedia.addEventListener("change", handleOrientationChange);
  } else {
    (orientationMedia as LegacyMediaQueryList).addListener?.(handleOrientationChange);
  }
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", syncDeviceMode);
  if (orientationMedia) {
    if (typeof orientationMedia.removeEventListener === "function") {
      orientationMedia.removeEventListener("change", handleOrientationChange);
    } else {
      (orientationMedia as LegacyMediaQueryList).removeListener?.(handleOrientationChange);
    }
  }
  document.documentElement.removeAttribute("data-device-mode");
  document.body.removeAttribute("data-device-mode");
});
</script>

<template>
  <div :class="['app-shell', `app-shell--${deviceMode}`]">
    <RouterView v-slot="{ Component, route }">
      <Transition name="page-fade" mode="out-in">
        <component :is="Component" :key="route.fullPath" />
      </Transition>
    </RouterView>
  </div>
</template>
