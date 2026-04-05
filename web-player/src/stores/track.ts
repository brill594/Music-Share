import { computed, ref } from "vue";
import { defineStore } from "pinia";

import { HttpError, downloadAudio, fetchTrack, toUserMessage } from "@/services/api";
import type { DownloadProgress, LoadingPhase, PublicTrack, TrackLoadResult, ViewState } from "@/types/share";

const initialProgress = (): DownloadProgress => ({
  loaded: 0,
  total: null,
  percent: null,
  done: false,
});

export const useTrackStore = defineStore("track", () => {
  const currentCode = ref("");
  const track = ref<PublicTrack | null>(null);
  const audioUrl = ref("");
  const viewState = ref<ViewState>("idle");
  const phase = ref<LoadingPhase>(null);
  const downloadProgress = ref<DownloadProgress>(initialProgress());
  const errorMessage = ref("");
  let activeController: AbortController | null = null;

  const hasTrack = computed(() => track.value !== null);
  const hasAudio = computed(() => audioUrl.value.length > 0);

  function resetProgress(): void {
    downloadProgress.value = initialProgress();
  }

  function clearAudioUrl(): void {
    if (audioUrl.value) {
      URL.revokeObjectURL(audioUrl.value);
      audioUrl.value = "";
    }
  }

  function cancelLoad(): void {
    activeController?.abort();
    activeController = null;
  }

  async function load(shareCode: string): Promise<TrackLoadResult> {
    const normalizedCode = shareCode.trim();
    currentCode.value = normalizedCode;

    cancelLoad();
    clearAudioUrl();
    resetProgress();
    errorMessage.value = "";
    track.value = null;
    viewState.value = "loading";
    phase.value = "metadata";

    if (!normalizedCode) {
      track.value = null;
      viewState.value = "error";
      phase.value = null;
      errorMessage.value = "缺少分享码。";
      return "not_found";
    }

    const controller = new AbortController();
    activeController = controller;

    try {
      const payload = await fetchTrack(normalizedCode, controller.signal);
      if (activeController !== controller) {
        return "aborted";
      }

      track.value = payload;
      phase.value = "audio";

      const blob = await downloadAudio(
        payload.stream_url,
        payload.audio_mime,
        controller.signal,
        (progress) => {
          if (activeController === controller) {
            downloadProgress.value = progress;
          }
        },
      );

      if (activeController !== controller) {
        return "aborted";
      }

      audioUrl.value = URL.createObjectURL(blob);
      viewState.value = "ready";
      phase.value = null;
      return "ready";
    } catch (error) {
      if (controller.signal.aborted) {
        return "aborted";
      }

      clearAudioUrl();

      if (error instanceof HttpError && error.scope === "metadata" && error.status === 410) {
        viewState.value = "expired";
        phase.value = null;
        errorMessage.value = "";
        return "expired";
      }

      if (error instanceof HttpError && error.scope === "metadata" && error.status === 404) {
        track.value = null;
        viewState.value = "error";
        phase.value = null;
        errorMessage.value = "分享链接不存在。";
        return "not_found";
      }

      viewState.value = "error";
      phase.value = null;
      errorMessage.value = toUserMessage(error);
      return "error";
    } finally {
      if (activeController === controller) {
        activeController = null;
      }
    }
  }

  async function retry(): Promise<TrackLoadResult> {
    if (!currentCode.value) {
      return "not_found";
    }
    return load(currentCode.value);
  }

  function teardown(): void {
    cancelLoad();
    clearAudioUrl();
    currentCode.value = "";
    track.value = null;
    viewState.value = "idle";
    phase.value = null;
    errorMessage.value = "";
    resetProgress();
  }

  return {
    currentCode,
    track,
    audioUrl,
    viewState,
    phase,
    downloadProgress,
    errorMessage,
    hasTrack,
    hasAudio,
    load,
    retry,
    cancelLoad,
    teardown,
  };
});
