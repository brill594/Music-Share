import type { DeviceMode } from "@/types/device";

const PHONE_UA_PATTERN = /iPhone|iPod|webOS|BlackBerry|IEMobile|Opera Mini|Windows Phone/i;
const TABLET_UA_PATTERN = /iPad|Tablet|PlayBook|Kindle|Silk/i;
const ANDROID_UA_PATTERN = /Android/i;
const MOBILE_TOKEN_PATTERN = /Mobile/i;
const MACINTOSH_UA_PATTERN = /Macintosh/i;

function isTabletOrFoldableLike(
  userAgent: string,
  viewportWidth: number,
  viewportHeight: number,
  maxTouchPoints: number,
): boolean {
  const smallerEdge = Math.min(viewportWidth, viewportHeight);
  const largerEdge = Math.max(viewportWidth, viewportHeight);

  if (TABLET_UA_PATTERN.test(userAgent)) {
    return true;
  }

  // iPadOS desktop UA still exposes touch points.
  if (MACINTOSH_UA_PATTERN.test(userAgent) && maxTouchPoints > 1) {
    return true;
  }

  if (ANDROID_UA_PATTERN.test(userAgent) && !MOBILE_TOKEN_PATTERN.test(userAgent)) {
    return true;
  }

  // Expanded foldables and compact tablets often still carry a mobile UA token.
  if (maxTouchPoints > 1 && smallerEdge >= 600 && largerEdge >= 780) {
    return true;
  }

  return false;
}

function isPhoneLike(
  userAgent: string,
  viewportWidth: number,
  viewportHeight: number,
  maxTouchPoints: number,
): boolean {
  const smallerEdge = Math.min(viewportWidth, viewportHeight);

  if (PHONE_UA_PATTERN.test(userAgent)) {
    return true;
  }

  if (ANDROID_UA_PATTERN.test(userAgent) && MOBILE_TOKEN_PATTERN.test(userAgent) && smallerEdge < 600) {
    return true;
  }

  if (maxTouchPoints > 1 && smallerEdge > 0 && smallerEdge < 600) {
    return true;
  }

  return false;
}

export function detectDeviceMode(
  userAgent: string = navigator.userAgent,
  viewportWidth: number = window.innerWidth,
  viewportHeight: number = window.innerHeight,
  maxTouchPoints: number = navigator.maxTouchPoints,
): DeviceMode {
  if (isTabletOrFoldableLike(userAgent, viewportWidth, viewportHeight, maxTouchPoints)) {
    return viewportWidth > viewportHeight ? "desktop" : "mobile";
  }

  if (isPhoneLike(userAgent, viewportWidth, viewportHeight, maxTouchPoints)) {
    return "mobile";
  }

  if (viewportWidth > 0 && viewportWidth < 680) {
    return "mobile";
  }

  return "desktop";
}

export function applyDeviceMode(mode: DeviceMode): void {
  document.documentElement.dataset.deviceMode = mode;
  document.body.dataset.deviceMode = mode;
}
