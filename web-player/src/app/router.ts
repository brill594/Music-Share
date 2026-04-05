import { createRouter, createWebHistory } from "vue-router";

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: "/expired",
      name: "expired",
      component: () => import("@/pages/ExpiredPage.vue"),
    },
    {
      path: "/:shareCode",
      name: "track",
      component: () => import("@/pages/TrackPage.vue"),
    },
    {
      path: "/:pathMatch(.*)*",
      name: "not-found",
      component: () => import("@/pages/NotFoundPage.vue"),
    },
  ],
});
