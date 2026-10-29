import * as amplitude from "@amplitude/unified";
import { appHostFrom } from "@/shared/bridge/bridge";
import { clientPropertiesFrom } from "@/shared/bridge/clientProperties";
import { clientPropertiesPluginFor } from "./clientPropertiesPlugin";
import { isAnalyticsOptOutChange, loadAnalyticsOptOut, storeAnalyticsOptOut } from "./optOutStorage";

interface Engagement {
  disable(): void;
  enable(): void;
}

let initialized = false;
let optedOut = false;
let memberId: string | null = null;
const optOutListeners = new Set<() => void>();

export function initAnalytics() {
  if (typeof window === "undefined") {
    return;
  }

  optedOut = loadAnalyticsOptOut();
  window.addEventListener("storage", (event) => {
    if (isAnalyticsOptOutChange(event)) followOptOut(loadAnalyticsOptOut());
  });
  if (!optedOut) startAnalytics();
}

function startAnalytics() {
  if (initialized) return;
  if (!__AMPLITUDE_API_KEY__) {
    console.warn("WEBPACK_AMPLITUDE_API_KEY가 비어 있어 Amplitude 이벤트를 보내지 않습니다");
    return;
  }

  initialized = true;

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
    // initAll은 다른 SDK를 다 띄운 뒤에 이 값을 읽어서, 초기화 중에 끈 설정도 Guides & Surveys에 반영된다.
    engagement: {
      get skip() {
        return optedOut;
      },
    },
  });
  if (memberId !== null) amplitude.setUserId(memberId);
}

export function isAnalyticsEnabled() {
  return initialized && !optedOut;
}

export function isAnalyticsOptedOut() {
  return optedOut;
}

export function subscribeAnalyticsOptOut(listener: () => void) {
  optOutListeners.add(listener);
  return () => {
    optOutListeners.delete(listener);
  };
}

export function setAnalyticsMemberId(nextMemberId: string | null) {
  memberId = nextMemberId;
  if (!initialized) return;
  // 로그아웃에 reset()을 쓰면 device_id가 새로 생겨 같은 기기가 다른 사용자로 잡힌다.
  amplitude.setUserId(nextMemberId ?? undefined);
}

export function setAnalyticsOptOut(nextOptedOut: boolean) {
  storeAnalyticsOptOut(nextOptedOut);
  followOptOut(nextOptedOut);
}

function followOptOut(nextOptedOut: boolean) {
  optedOut = nextOptedOut;
  if (initialized) {
    amplitude.setOptOut(optedOut);
    // Guides & Surveys는 setOptOut을 따르지 않아서 따로 멈추고 켠다.
    const engagement = (window as Window & { engagement?: Engagement }).engagement;
    if (optedOut) engagement?.disable();
    else engagement?.enable();
  } else if (!optedOut) {
    startAnalytics();
  }
  optOutListeners.forEach((listener) => listener());
}
