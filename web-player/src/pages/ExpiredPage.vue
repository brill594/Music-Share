<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";

const route = useRoute();
const router = useRouter();

const shareCode = computed(() => {
  const rawValue = route.query.code;
  return typeof rawValue === "string" ? rawValue : "";
});

async function retry(): Promise<void> {
  if (shareCode.value) {
    await router.push({
      name: "track",
      params: {
        shareCode: shareCode.value,
      },
    });
    return;
  }

  await router.push({
    name: "not-found",
  });
}
</script>

<template>
  <div class="page-shell">
    <div class="page-shell__veil"></div>
    <div class="page-shell__grain"></div>

    <main class="page-shell__content">
      <section class="page-card state-page">
        <p class="state-page__eyebrow">Link Expired</p>
        <h1 class="state-page__title">这个试听链接已经失效</h1>
        <p class="state-page__body">
          分享内容可能已经过期，或者被上传方手动终止。该页面不保留长期内容，也不会继续提供音频访问。
        </p>
        <p v-if="shareCode" class="state-page__code">分享码：{{ shareCode }}</p>
        <div class="state-page__actions">
          <button class="state-page__primary" type="button" @click="void retry()">重新检测一次</button>
        </div>
      </section>
    </main>
  </div>
</template>
