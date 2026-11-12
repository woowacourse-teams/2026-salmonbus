import { appHostFrom } from "@/shared/bridge/bridge";
import { clientPropertiesFrom } from "@/shared/bridge/clientProperties";
import { clientPropertiesPluginFor } from "./clientPropertiesPlugin";
import { isAnalyticsOptOutChange, loadAnalyticsOptOut, storeAnalyticsOptOut } from "./optOutStorage";

interface Engagement {
  disable(): void;
  enable(): void;
}

type AmplitudeSdk = typeof import("@amplitude/unified");

let initialized = false;
let sdkLoading: Promise<AmplitudeSdk | null> | null = null;
let sdkFailed = false;
let optedOut = false;
let optOutCount = 0;
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

function afterWindowLoad(): Promise<void> {
  if (document.readyState === "complete") return Promise.resolve();
  return new Promise((resolve) => window.addEventListener("load", () => resolve(), { once: true }));
}

// SDK를 받지 못하면 null로 끝나서, 이어 붙인 쪽에서 처리되지 않은 거부가 생기지 않는다.
export function whenAnalyticsSdkReady(): Promise<AmplitudeSdk | null> | null {
  return sdkLoading;
}

export function callAnalyticsSdk(action: (amplitude: AmplitudeSdk) => void) {
  void sdkLoading?.then((amplitude) => {
    if (amplitude) action(amplitude);
  });
}

// SDK를 받는 동안 수집 거부를 켠 적이 있으면, 그 전에 호출한 이벤트도 보내지 않는다.
export function sendThroughAnalyticsSdk(send: (amplitude: AmplitudeSdk) => void) {
  const optOutCountOnCall = optOutCount;
  callAnalyticsSdk((amplitude) => {
    if (optOutCount === optOutCountOnCall && !optedOut) send(amplitude);
  });
}

// 받기와 시작 중 어디서 실패하든 null로 끝나서, 이어 붙인 쪽에서 처리되지 않은 거부가 생기지 않는다.
async function loadAndStartAmplitude(appVersion: string | undefined): Promise<AmplitudeSdk | null> {
  await afterWindowLoad();
  try {
    const amplitude = await import(/* webpackChunkName: "amplitude" */ "@amplitude/unified");
    amplitude.add(clientPropertiesPluginFor(clientPropertiesFrom(appHostFrom())));
    amplitude.initAll(__AMPLITUDE_API_KEY__, {
      analytics: {
        autocapture: true,
        // 받는 동안 수집 거부를 켰으면 꺼진 채로 시작한다. 예전에 끈 기록이 SDK 쿠키에 남아 있어도 이 값으로 덮는다.
        optOut: optedOut,
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
    return amplitude;
  } catch {
    sdkFailed = true;
    console.warn("Amplitude SDK를 불러오거나 시작하지 못해 이벤트를 보내지 않습니다");
    return null;
  }
}

function startAnalytics() {
  if (initialized) return;
  if (!__AMPLITUDE_API_KEY__) {
    console.warn("WEBPACK_AMPLITUDE_API_KEY가 비어 있어 Amplitude 이벤트를 보내지 않습니다");
    return;
  }

  initialized = true;

  const appVersion = document.querySelector<HTMLMetaElement>('meta[name="app-version"]')?.content;
  sdkLoading = loadAndStartAmplitude(appVersion);
}

export function isAnalyticsEnabled() {
  return initialized && !sdkFailed && !optedOut;
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
  // 로그아웃에 reset()을 쓰면 device_id가 새로 생겨 같은 기기가 다른 사용자로 잡힌다.
  callAnalyticsSdk((amplitude) => amplitude.setUserId(nextMemberId ?? undefined));
}

export function setAnalyticsOptOut(nextOptedOut: boolean) {
  storeAnalyticsOptOut(nextOptedOut);
  followOptOut(nextOptedOut);
}

function followOptOut(nextOptedOut: boolean) {
  if (nextOptedOut && !optedOut) optOutCount += 1;
  optedOut = nextOptedOut;
  if (sdkLoading) {
    callAnalyticsSdk((amplitude) => {
      amplitude.setOptOut(optedOut);
      // Guides & Surveys는 setOptOut을 따르지 않아서 따로 멈추고 켠다.
      const engagement = (window as Window & { engagement?: Engagement }).engagement;
      if (optedOut) engagement?.disable();
      else engagement?.enable();
    });
  } else if (!optedOut) {
    startAnalytics();
  }
  optOutListeners.forEach((listener) => listener());
}
