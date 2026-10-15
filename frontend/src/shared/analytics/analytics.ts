import * as amplitude from "@amplitude/unified";

const AMPLITUDE_API_KEY = process.env.WEBPACK_AMPLITUDE_API_KEY ?? "";

let initialized = false;

export function initAnalytics() {
  if (typeof window === "undefined" || initialized) {
    return;
  }

  if (!AMPLITUDE_API_KEY) {
    console.warn("WEBPACK_AMPLITUDE_API_KEY가 비어 있어 Amplitude 이벤트를 보내지 않습니다");
    return;
  }

  initialized = true;

  const appVersion = document.querySelector<HTMLMetaElement>('meta[name="app-version"]')?.content;

  amplitude.initAll(AMPLITUDE_API_KEY, {
    analytics: { autocapture: true, ...(appVersion ? { appVersion } : {}) },
    sessionReplay: { sampleRate: 1 },
  });
}

export function isAnalyticsEnabled() {
  return initialized;
}
