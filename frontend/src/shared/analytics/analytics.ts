import * as amplitude from "@amplitude/unified";
import { appHostFrom } from "@/shared/bridge/bridge";
import { clientPropertiesFrom } from "@/shared/bridge/clientProperties";
import { clientPropertiesPluginFor } from "./clientPropertiesPlugin";
import { isAnalyticsOptedOut, storeAnalyticsOptOut } from "./optOutStorage";

export { isAnalyticsOptedOut };

interface Engagement {
  disable(): void;
  enable(): void;
}

let initialized = false;
let sending = false;
let memberId: string | null = null;

export function initAnalytics() {
  if (typeof window === "undefined" || initialized) {
    return;
  }

  if (!__AMPLITUDE_API_KEY__) {
    console.warn("WEBPACK_AMPLITUDE_API_KEY가 비어 있어 Amplitude 이벤트를 보내지 않습니다");
    return;
  }

  // Guides & Surveys는 setOptOut과 상관없이 스크립트와 설정을 받아 와서, 끈 기기는 SDK를 아예 띄우지 않는다.
  if (isAnalyticsOptedOut()) return;

  initialized = true;
  sending = true;

  const appVersion = document.querySelector<HTMLMetaElement>('meta[name="app-version"]')?.content;

  amplitude.add(clientPropertiesPluginFor(clientPropertiesFrom(appHostFrom())));
  amplitude.initAll(__AMPLITUDE_API_KEY__, {
    analytics: {
      autocapture: true,
      // 예전에 끈 기록이 SDK 쿠키에 남아 있어도 켠 상태로 시작한다.
      optOut: false,
      // IP를 보내지 않으면 Amplitude가 IP로 지역도 추정하지 않는다.
      trackingOptions: { ipAddress: false },
      ...(appVersion ? { appVersion } : {}),
    },
    sessionReplay: { sampleRate: 1 },
  });
  if (memberId !== null) amplitude.setUserId(memberId);
}

export function isAnalyticsEnabled() {
  return sending;
}

export function setAnalyticsMemberId(nextMemberId: string | null) {
  memberId = nextMemberId;
  if (!initialized) return;
  // 로그아웃에 reset()을 쓰면 device_id가 새로 생겨 같은 기기가 다른 사용자로 잡힌다.
  amplitude.setUserId(nextMemberId ?? undefined);
}

export function setAnalyticsOptOut(optedOut: boolean) {
  storeAnalyticsOptOut(optedOut);
  if (!initialized) {
    if (!optedOut) initAnalytics();
    return;
  }
  sending = !optedOut;
  amplitude.setOptOut(optedOut);
  const engagement = (window as Window & { engagement?: Engagement }).engagement;
  if (optedOut) engagement?.disable();
  else engagement?.enable();
}
