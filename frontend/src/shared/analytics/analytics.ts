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

// 시작이 나중에 실패해도 그 뒤에 부른 쪽은 null을 받도록, 부를 때마다 실패 여부를 다시 본다.
export function whenAnalyticsSdkReady(): Promise<AmplitudeSdk | null> | null {
  return sdkLoading?.then((amplitude) => (sdkFailed ? null : amplitude)) ?? null;
}

// callAnalyticsSdk끼리는 부른 순서대로 실행된다.
export function callAnalyticsSdk(action: (amplitude: AmplitudeSdk) => void) {
  void whenAnalyticsSdkReady()?.then((amplitude) => {
    if (amplitude) action(amplitude);
  });
}

// 부른 뒤에 수집 거부를 켠 적이 있으면, 거부를 다시 풀어도 그 호출은 보내지 않는다.
// SDK를 기다리는 동안 일어난 거부 전환을 보내기 직전에 한 곳에서 판단한다.
export function createSendPermission(): () => boolean {
  const optOutCountOnCall = optOutCount;
  return () => isAnalyticsEnabled() && optOutCount === optOutCountOnCall;
}

export function sendThroughAnalyticsSdk(send: (amplitude: AmplitudeSdk) => void) {
  const isSendAllowed = createSendPermission();
  callAnalyticsSdk((amplitude) => {
    if (isSendAllowed()) send(amplitude);
  });
}

function markSdkFailed() {
  sdkFailed = true;
  console.warn("Amplitude SDK를 불러오거나 시작하지 못해 이벤트를 보내지 않습니다");
}

// 받기나 시작 호출이 실패하면 null로 끝나고, 시작이 나중에 거부하는 경우는 markSdkFailed가 따로 받는다.
async function loadAndStartAmplitude(appVersion: string | undefined): Promise<AmplitudeSdk | null> {
  await afterWindowLoad();
  try {
    const amplitude = await import(/* webpackChunkName: "amplitude" */ "@amplitude/unified");
    amplitude.add(clientPropertiesPluginFor(clientPropertiesFrom(appHostFrom())));
    // initAll은 Session Replay 같은 플러그인까지 기다린 뒤에 끝나서, 기다리면 먼저 부른 이벤트가 그만큼 늦게 넘어간다. 거부만 받아 실패로 돌린다.
    void amplitude
      .initAll(__AMPLITUDE_API_KEY__, {
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
      })
      .catch(markSdkFailed);
    if (memberId !== null) amplitude.setUserId(memberId);
    return amplitude;
  } catch {
    markSdkFailed();
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
    // 시작이 실패했어도 autocapture와 Session Replay가 이미 돌고 있을 수 있어서, 거부는 실패 여부와 상관없이 SDK에 전한다.
    void sdkLoading.then((amplitude) => {
      if (!amplitude) return;
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
