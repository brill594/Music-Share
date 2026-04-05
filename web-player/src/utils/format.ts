const zhDateFormatter = new Intl.DateTimeFormat("zh-CN", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
});

export function formatDurationSeconds(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) {
    return "00:00";
  }

  const rounded = Math.floor(seconds);
  const hours = Math.floor(rounded / 3600);
  const minutes = Math.floor((rounded % 3600) / 60);
  const remain = rounded % 60;

  if (hours > 0) {
    return [hours, minutes, remain].map((part) => String(part).padStart(2, "0")).join(":");
  }

  return [minutes, remain].map((part) => String(part).padStart(2, "0")).join(":");
}

export function formatDurationMs(durationMs: number): string {
  return formatDurationSeconds(durationMs / 1000);
}

export function formatDateTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return zhDateFormatter.format(date);
}

export function formatRelativeExpiry(value: string): string {
  const expiry = new Date(value);
  if (Number.isNaN(expiry.getTime())) {
    return "过期时间未知";
  }

  const diffMs = expiry.getTime() - Date.now();
  if (diffMs <= 0) {
    return "已过期";
  }

  const diffMinutes = Math.floor(diffMs / 60000);
  if (diffMinutes < 1) {
    return "不到 1 分钟后过期";
  }
  if (diffMinutes < 60) {
    return `${diffMinutes} 分钟后过期`;
  }

  const diffHours = Math.floor(diffMinutes / 60);
  if (diffHours < 24) {
    return `${diffHours} 小时后过期`;
  }

  const diffDays = Math.floor(diffHours / 24);
  return `${diffDays} 天后过期`;
}

export function formatBytes(value: number): string {
  if (!Number.isFinite(value) || value <= 0) {
    return "0 B";
  }

  const units = ["B", "KB", "MB", "GB"];
  let size = value;
  let index = 0;
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024;
    index += 1;
  }

  return `${size.toFixed(size >= 10 || index === 0 ? 0 : 1)} ${units[index]}`;
}

export function formatMimeLabel(value: string): string {
  const normalized = value.toLowerCase();
  if (normalized.includes("ogg")) {
    return "OGG";
  }
  if (normalized.includes("mpeg") || normalized.includes("mp3")) {
    return "MP3";
  }
  if (normalized.includes("aac")) {
    return "AAC";
  }
  if (normalized.includes("m4a") || normalized.includes("mp4")) {
    return "M4A";
  }

  return normalized.replace("audio/", "").toUpperCase();
}
