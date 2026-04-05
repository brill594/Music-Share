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
  if (!shareCode.value) {
    return;
  }

  await router.push({
    name: "track",
    params: {
      shareCode: shareCode.value,
    },
  });
}
</script>

<template>
  <div class="page-shell">
    <div class="page-shell__veil"></div>
    <div class="page-shell__grain"></div>

    <main class="page-shell__content">
      <section class="page-card state-page">
        <p class="state-page__eyebrow">Track Missing</p>
        <h1 class="state-page__title">没有找到对应的分享内容</h1>
        <p class="state-page__body">
          这个地址可能输入有误，也可能该分享已经不可用。
        </p>
        <div class="state-page__actions">
          <button
            v-if="shareCode"
            class="state-page__primary"
            type="button"
            @click="void retry()"
          >
            重新请求一次
          </button>
        </div>
      </section>
    </main>
  </div>
</template>
