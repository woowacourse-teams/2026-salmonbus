import * as amplitude from "@amplitude/unified";
import { appHostFrom } from "@/shared/bridge/bridge";
import { clientPropertiesFrom } from "@/shared/bridge/clientProperties";
import { clientPropertiesPluginFor } from "./clientPropertiesPlugin";

let initialized = false;

export function initAnalytics() {
  if (typeof window === "undefined" || initialized) {
    return;
  }

  if (!__AMPLITUDE_API_KEY__) {
    console.warn("WEBPACK_AMPLITUDE_API_KEY가 비어 있어 Amplitude 이벤트를 보내지 않습니다");
    return;
  }

  initialized = true;

  const appVersion = document.querySelector<HTMLMetaElement>('meta[name="app-version"]')?.content;

  amplitude.add(clientPropertiesPluginFor(clientPropertiesFrom(appHostFrom())));
  amplitude.initAll(__AMPLITUDE_API_KEY__, {
    analytics: { autocapture: true, ...(appVersion ? { appVersion } : {}) },
    sessionReplay: { sampleRate: 1 },
  });
}

export function isAnalyticsEnabled() {
  return initialized;
}

export function setAnalyticsMemberId(memberId: string | null) {
  if (!initialized) return;
  // 로그아웃에 reset()을 쓰면 device_id가 새로 생겨 같은 기기가 다른 사용자로 잡힌다.
  amplitude.setUserId(memberId ?? undefined);
}
