export type ShareStatus = "active" | "expired" | "terminated";

export type ViewState = "idle" | "loading" | "ready" | "expired" | "error";

export type LoadingPhase = "metadata" | "audio" | null;

export type TrackLoadResult = "ready" | "expired" | "not_found" | "error" | "aborted";

export interface DownloadProgress {
  loaded: number;
  total: number | null;
  percent: number | null;
  done: boolean;
}

export interface PublicTrack {
  share_code: string;
  title: string;
  artist: string;
  album: string;
  duration_ms: number;
  audio_mime: string;
  share_url: string;
  track_url: string;
  stream_url: string;
  cover_url: string | null;
  background_url: string | null;
  created_at: string;
  expires_at: string;
  status: ShareStatus;
}
