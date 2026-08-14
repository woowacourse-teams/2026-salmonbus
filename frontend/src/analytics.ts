import * as amplitude from "@amplitude/unified";

const AMPLITUDE_API_KEY = process.env.WEBPACK_AMPLITUDE_API_KEY ?? "";

let initialized = false;

export function initAnalytics() {
  if (typeof window === "undefined" || initialized) {
    return;
  }

  if (!AMPLITUDE_API_KEY) {
    console.warn("Amplitude API key missing — analytics disabled");
    return;
  }

  initialized = true;

  amplitude.initAll(AMPLITUDE_API_KEY, {
    analytics: { autocapture: true },
    sessionReplay: { sampleRate: 1 },
  });
}
